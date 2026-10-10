package cn.zaozhi.tool;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Workspace {
    private final Path root;
    public Workspace(Path dir) throws Exception { Files.createDirectories(dir); root=dir.toRealPath(); }
    public Path resolve(String name) throws Exception {
        Path candidate=root.resolve(name).normalize();
        if(!candidate.startsWith(root)) throw new IllegalArgumentException("路径超出本次工作区");
        Path current=root;
        for(Path part:root.relativize(candidate)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current)) throw new IllegalArgumentException("工作区不允许符号链接");
            if(Files.exists(current) && !current.toRealPath().startsWith(root)) throw new IllegalArgumentException("路径超出本次工作区");
        }
        return candidate;
    }
    public ArrayNode list(String name) throws Exception {
        try(var paths=Files.list(resolve(name))) { return Json.tree(paths.limit(100).map(p->p.getFileName().toString()).sorted().toList()).deepCopy(); }
    }
    public String read(String name) throws Exception {
        Path p=resolve(name);
        if(Files.size(p)>64_000) throw new IllegalArgumentException("文件超过 64KB");
        return Files.readString(p,StandardCharsets.UTF_8);
    }
    public void write(String name,String content) throws Exception {
        Path p=resolve(name);
        if(content.getBytes(StandardCharsets.UTF_8).length>64_000) throw new IllegalArgumentException("文件超过 64KB");
        if(!Files.exists(p)) {
            try(var paths=Files.walk(root,8)) { if(paths.filter(Files::isRegularFile).count()>=100) throw new IllegalArgumentException("工作区文件数已达上限"); }
        }
        Files.createDirectories(p.getParent()); Files.writeString(p,content,StandardCharsets.UTF_8);
    }
    public ArrayNode search(String keyword,String dir) throws Exception {
        ArrayNode results=Json.arr();
        try(var paths=Files.walk(resolve(dir),8)) {
            for(Path p:paths.limit(500).toList()) {
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) || Files.size(p)>64_000) continue;
                String content=read(root.relativize(p).toString());
                String[] lines=content.split("\\R");
                for(int i=0;i<lines.length&&results.size()<100;i++)
                    if(lines[i].toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) results.add(Json.object("path",root.relativize(p).toString(),"line",i+1,"text",NewsClient.clip(lines[i],300)));
                if(results.size()>=100) break;
            }
        }
        return results;
    }
    public String bash(String command,long deadline) throws Exception {
        if(!Set.of("pwd","ls").contains(command)) throw new IllegalArgumentException("仅允许 pwd 或 ls，不能添加参数、管道或其他命令");
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> args=windows?List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe","-NoProfile","-NonInteractive","-Command",
            command.equals("pwd")?"(Get-Location).Path":"Get-ChildItem -Name") : List.of("/bin/sh","-c",command);
        ProcessBuilder builder=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        if(windows) builder.environment().put("SystemRoot","C:\\Windows");
        Path output=root.resolve(".shell-output");
        builder.redirectOutput(output.toFile());
        Process process=builder.start();
        try {
            long millis=Math.min(5000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
            if(millis<=0 || !process.waitFor(millis,TimeUnit.MILLISECONDS)) throw new IllegalStateException("Shell 执行超时");
            if(process.exitValue()!=0) throw new IllegalStateException("Shell 执行失败");
            return read(".shell-output");
        } finally { if(process.isAlive()) process.destroyForcibly(); Files.deleteIfExists(output); }
    }
}
