package cn.zaozhi.tool;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Executes immutable scripts, never interpolates a model command into shell code. */
public final class ShellExecutor {
    private static final int OUTPUT_LIMIT = 16 * 1024;
    private final Settings settings;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");

    public ShellExecutor(Settings settings) { this.settings = settings; }

    public ObjectNode execute(String command, Workspace workspace, long deadline, Runnable check, Consumer<ObjectNode> audit) throws Exception {
        if (!Set.of("pwd", "ls").contains(command))
            throw new ShellFailure("COMMAND_DENIED", "仅允许完整的 pwd 或 ls 字面值", Json.obj());
        String script = windows
            ? command.equals("pwd") ? "(Get-Location).Path" : "Get-ChildItem -LiteralPath . -Name"
            : command.equals("pwd") ? "pwd -P" : "ls -1 -- .";
        return executeScript(command, script, workspace, deadline, check, audit);
    }

    // Package-private fault fixture entry; never exposed in tool schemas or HTTP APIs.
    ObjectNode executeScript(String command, String script, Workspace workspace, long deadline, Runnable check, Consumer<ObjectNode> audit) throws Exception {
        check.run();
        long start = System.nanoTime();
        long end = Math.min(deadline, start + TimeUnit.MILLISECONDS.toNanos(settings.integer("SHELL_TIMEOUT_MS", 5000)));
        Path root = workspace.resolve(".").toRealPath();
        Path shell = shellPath();
        String gated = windows
            ? "$ErrorActionPreference = 'Stop'; [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; [Console]::In.ReadLine() | Out-Null; " + script
            : "IFS= read -r _ || :; " + script;
        List<String> argv = windows
            ? List.of(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", gated)
            : List.of("/usr/bin/setsid", "--wait", shell.toString(), "--noprofile", "--norc", "-c", gated);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile());
        Map<String,String> environment = builder.environment();
        environment.clear();
        Path temporary = root.resolve(".shell-tmp");
        Files.createDirectories(temporary);
        if (windows) {
            Path system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows")).toRealPath();
            environment.put("SystemRoot", system.toString());
            environment.put("WINDIR", system.toString());
            environment.put("PATH", shell.getParent() + ";" + system.resolve("System32"));
            environment.put("TEMP", temporary.toString());
            environment.put("TMP", temporary.toString());
        } else {
            if (!Files.isExecutable(Path.of("/usr/bin/setsid")))
                throw new ShellFailure("SHELL_UNAVAILABLE", "Linux 需要 /usr/bin/setsid 管理进程组", Json.obj());
            environment.put("PATH", "/usr/bin:/bin");
            environment.put("LANG", "C.UTF-8");
            environment.put("TMPDIR", temporary.toString());
        }
        Process process;
        try { process = builder.start(); }
        catch (IOException e) { throw new ShellFailure("SHELL_START_FAILED", "无法启动配置的 shell", Json.obj()); }
        ExecutorService readers = Executors.newFixedThreadPool(2, task -> { Thread thread = new Thread(task, "shell-output"); thread.setDaemon(true); return thread; });
        Output output = new Output();
        String errorCode = null;
        Integer exit = null;
        boolean restoredInterrupt = false;
        Exception fatal = null;
        try (ProcessSupervisor supervisor = new ProcessSupervisor(process)) {
            Future<?> stdout = readers.submit(() -> output.read(process.getInputStream(), output.stdout));
            Future<?> stderr = readers.submit(() -> output.read(process.getErrorStream(), output.stderr));
            try {
                supervisor.attach(windows, end);
                audit.accept(Json.object("phase", "started", "pid", process.pid(), "shell_type", windows ? "PowerShell" : "Bash", "command", command));
                while (process.isAlive()) {
                    check.run();
                    if (output.exceeded.get()) { errorCode = "SHELL_OUTPUT_LIMIT"; break; }
                    if (System.nanoTime() >= end) { errorCode = "SHELL_TIMEOUT"; break; }
                    process.waitFor(20, TimeUnit.MILLISECONDS);
                }
                if (!process.isAlive()) exit = process.exitValue();
            } catch (InterruptedException e) {
                restoredInterrupt = true;
                fatal = e;
                errorCode = "SHELL_CANCELLED";
            } catch (Exception e) {
                fatal = e;
                errorCode = e instanceof cn.zaozhi.agent.AgentFailure ? "SHELL_CANCELLED" : "SHELL_SUPERVISION_FAILED";
            } finally { supervisor.close(); }
            restoredInterrupt |= Thread.interrupted();
            try { stdout.get(2, TimeUnit.SECONDS); stderr.get(2, TimeUnit.SECONDS); }
            catch (Exception e) { if (errorCode == null) errorCode = "SHELL_OUTPUT_READ_FAILED"; }
        } finally {
            readers.shutdownNow();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
        if (output.exceeded.get() && errorCode == null) errorCode = "SHELL_OUTPUT_LIMIT";
        if (exit != null && exit != 0 && errorCode == null) errorCode = "SHELL_EXIT_NONZERO";
        ObjectNode result = Json.object("pid", process.pid(), "shell_type", windows ? "PowerShell" : "Bash",
            "supervisor", windows ? "windows_job_object" : "linux_process_group", "command", command, "exit_code", exit,
            "stdout", safeOutput(output.stdout, root), "stderr", safeOutput(output.stderr, root),
            "duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), "output_bytes", Math.min(output.bytes.get(), OUTPUT_LIMIT),
            "truncated", output.exceeded.get(), "path_redacted", true, "process_reaped", !process.isAlive(), "error_code", errorCode);
        audit.accept(result.deepCopy().put("phase", "finished"));
        if (restoredInterrupt) Thread.currentThread().interrupt();
        if (fatal instanceof cn.zaozhi.agent.AgentFailure || fatal instanceof InterruptedException) throw fatal;
        if (errorCode != null) throw new ShellFailure(errorCode, switch (errorCode) {
            case "SHELL_TIMEOUT" -> "shell 执行超过期限，进程树已终止";
            case "SHELL_OUTPUT_LIMIT" -> "shell 输出超过 16 KiB，进程树已终止";
            case "SHELL_EXIT_NONZERO" -> "shell 以非零退出码结束";
            default -> "shell 执行或进程监管失败";
        }, result);
        check.run();
        return result;
    }

    private Path shellPath() throws Exception {
        String fallback = windows
            ? Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()
            : "/bin/bash";
        Path configured = Path.of(settings.get("SHELL_EXECUTABLE", fallback));
        if (!configured.isAbsolute() || !Files.isRegularFile(configured) || !Files.isExecutable(configured))
            throw new ShellFailure("SHELL_UNAVAILABLE", "shell 必须为管理员配置的有效绝对路径", Json.obj());
        return configured.toRealPath();
    }

    private String safeOutput(ByteArrayOutputStream stream, Path root) {
        synchronized (stream) {
            return settings.redact(stream.toString(StandardCharsets.UTF_8).replace(root.toString(), "/workspace"));
        }
    }
    public static final class ShellFailure extends Exception {
        public final String code;
        public final ObjectNode data;
        ShellFailure(String code, String message, ObjectNode data) { super(message); this.code = code; this.data = data; }
    }
    private static final class Output {
        final AtomicInteger bytes = new AtomicInteger();
        final AtomicBoolean exceeded = new AtomicBoolean();
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        void read(InputStream input, ByteArrayOutputStream target) {
            try {
                byte[] buffer = new byte[1024];
                for (int size; (size = input.read(buffer)) != -1;) {
                    int previous = bytes.getAndAdd(size);
                    int accepted = Math.max(0, Math.min(size, OUTPUT_LIMIT - previous));
                    synchronized (target) { target.write(buffer, 0, accepted); }
                    if (previous + size > OUTPUT_LIMIT) { exceeded.set(true); return; }
                }
            } catch (IOException ignored) { /* Closing a terminated tree closes its pipes. */ }
        }
    }
}
