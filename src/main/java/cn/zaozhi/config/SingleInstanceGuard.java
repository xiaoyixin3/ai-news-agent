package cn.zaozhi.config;

import java.nio.channels.*;
import java.nio.file.*;

/** Acquire before migration/recovery and keep until workers and SQLite are closed. */
public final class SingleInstanceGuard implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public SingleInstanceGuard(Path data) throws Exception {
        Files.createDirectories(data);
        channel=FileChannel.open(data.resolve("application.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired=channel.tryLock(); } catch(OverlappingFileLockException e) { acquired=null; }
        if(acquired==null) {channel.close(); throw new IllegalStateException("此数据目录已有服务运行，请先停止原服务");}
        lock=acquired;
    }
    public void close() throws Exception {lock.release();channel.close();}
}
