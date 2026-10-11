package cn.zaozhi.tool;

import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** The fixed script waits on stdin until the process tree is supervised. */
final class ProcessSupervisor implements AutoCloseable {
    private static final Set<ProcessSupervisor> ACTIVE = ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ACTIVE.forEach(ProcessSupervisor::close), "shell-cleanup"));
    }
    private final Process process;
    private Pointer job;
    private boolean group;
    private boolean closed;

    ProcessSupervisor(Process process) { this.process = process; ACTIVE.add(this); }

    void attach(boolean windows, long deadline) throws Exception {
        if (windows) {
            JobApi api = JobApi.INSTANCE;
            job = api.CreateJobObjectW(null, null);
            if (job == null) throw new IOException("无法创建 Windows Job Object");
            ExtendedLimits limits = new ExtendedLimits();
            limits.basic.flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, no breakaway.
            limits.write();
            if (api.SetInformationJobObject(job, 9, limits.getPointer(), limits.size()) == 0)
                throw new IOException("无法设置进程树回收限制");
            Pointer handle = api.OpenProcess(0x0100 | 0x0001, 0, Math.toIntExact(process.pid()));
            if (handle == null) throw new IOException("无法打开 shell 进程");
            try {
                if (api.AssignProcessToJobObject(job, handle) == 0)
                    throw new IOException("无法将 shell 纳入受控进程树");
            } finally { api.CloseHandle(handle); }
        } else {
            while (Posix.INSTANCE.getpgid(Math.toIntExact(process.pid())) != process.pid()) {
                if (!process.isAlive() || System.nanoTime() >= deadline)
                    throw new IOException("无法创建受控 shell 进程组");
                Thread.sleep(5);
            }
            group = true;
        }
        // EOF releases the trusted startup gate only after supervision is attached.
        process.getOutputStream().close();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        boolean interrupted = Thread.interrupted();
        try {
            if (job != null) {
                JobApi.INSTANCE.TerminateJobObject(job, 1);
                JobApi.INSTANCE.CloseHandle(job);
                job = null;
            } else if (group) {
                Posix.INSTANCE.kill(-Math.toIntExact(process.pid()), 9);
            }
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) { interrupted = true; }
        finally {
            ACTIVE.remove(this);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public interface JobApi extends StdCallLibrary {
        JobApi INSTANCE = Native.load("kernel32", JobApi.class);
        Pointer CreateJobObjectW(Pointer security, WString name);
        int SetInformationJobObject(Pointer job, int informationClass, Pointer information, int length);
        Pointer OpenProcess(int access, int inherit, int pid);
        int AssignProcessToJobObject(Pointer job, Pointer process);
        int TerminateJobObject(Pointer job, int exitCode);
        int CloseHandle(Pointer handle);
    }
    public interface Posix extends Library {
        Posix INSTANCE = Native.load("c", Posix.class);
        int getpgid(int pid);
        int kill(int pid, int signal);
    }
    public static class SizeT extends IntegerType {
        public SizeT() { super(Native.SIZE_T_SIZE, 0, true); }
    }
    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minimum", "maximum", "active", "affinity", "priority", "scheduling"})
    public static class BasicLimits extends Structure {
        public long processTime, jobTime;
        public int flags;
        public SizeT minimum = new SizeT(), maximum = new SizeT();
        public int active;
        public SizeT affinity = new SizeT();
        public int priority, scheduling;
    }
    @Structure.FieldOrder({"readOperations", "writeOperations", "otherOperations", "readBytes", "writeBytes", "otherBytes"})
    public static class IoCounters extends Structure {
        public long readOperations, writeOperations, otherOperations, readBytes, writeBytes, otherBytes;
    }
    @Structure.FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static class ExtendedLimits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public IoCounters io = new IoCounters();
        public SizeT processMemory = new SizeT(), jobMemory = new SizeT(), peakProcessMemory = new SizeT(), peakJobMemory = new SizeT();
    }
}
