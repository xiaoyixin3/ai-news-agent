package cn.zaozhi.tool;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Workspace {
    private final Path root;private final long quota;
    public Workspace(Path dir)throws Exception{this(dir,10*1024*1024);}
    public Workspace(Path dir,long quota)throws Exception{Files.createDirectories(dir);root=dir.toRealPath();this.quota=quota;}
    public Path resolve(String name)throws Exception {
        if(name==null||name.isBlank()||name.startsWith("/")||name.startsWith("\\")||name.contains(":")||Arrays.asList(name.replace('\\','/').split("/",-1)).contains(".."))throw new IllegalArgumentException("只允许本次工作区虚拟相对路径");
        Path candidate=root.resolve(name).normalize();if(!candidate.startsWith(root))throw new IllegalArgumentException("路径超出工作区");Path current=root;
        for(Path part:root.relativize(candidate)){current=current.resolve(part);if(Files.isSymbolicLink(current)||(Files.exists(current)&&!current.toRealPath().startsWith(root)))throw new IllegalArgumentException("禁止符号链接、junction 或越界路径");}return candidate;
    }
    public ArrayNode list(String name)throws Exception{try(var paths=Files.list(resolve(name))){return Json.tree(paths.limit(100).map(p->p.getFileName().toString()).sorted().toList()).deepCopy();}}
    public String read(String name)throws Exception{return read(name,0,8000);}
    public String read(String name,int offset,int max)throws Exception{if(offset<0||max<1||max>8000)throw new IllegalArgumentException("读取范围无效");Path p=resolve(name);if(Files.size(p)>2*1024*1024)throw new IllegalArgumentException("文件过大");String content=Files.readString(p,StandardCharsets.UTF_8);if(offset>=content.length())return "";return content.substring(offset,Math.min(content.length(),offset+max));}
    public void write(String name,String content)throws Exception{String virtual=name.replace('\\','/');if(!virtual.startsWith("notes/")&&!virtual.startsWith("drafts/"))throw new IllegalArgumentException("仅 notes/ 与 drafts/ 可写");if(content.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("草稿单文件超过 64 KiB");systemWrite(name,content);}
    public synchronized void systemWrite(String name,String content)throws Exception {
        Path p=resolve(name);byte[] bytes=content.getBytes(StandardCharsets.UTF_8);long total=0;try(var paths=Files.walk(root)){for(Path f:paths.filter(x->Files.isRegularFile(x,LinkOption.NOFOLLOW_LINKS)).toList()){resolve(root.relativize(f).toString());total+=Files.size(f);}}
        long old=Files.exists(p)?Files.size(p):0;if(total-old+bytes.length>quota)throw new IllegalArgumentException("本次工作区累计容量超限");Files.createDirectories(p.getParent());resolve(name);Path temp=Files.createTempFile(p.getParent(),".write-",".tmp");try{Files.write(temp,bytes);Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    public ArrayNode search(String keyword,String dir)throws Exception{return search(keyword,dir,20);}
    public ArrayNode search(String keyword,String dir,int limit)throws Exception {
        if(limit<1||limit>20)throw new IllegalArgumentException("最多 20 条命中");ArrayNode results=Json.arr();try(var paths=Files.walk(resolve(dir),12)){
            for(Path p:paths.limit(500).toList()){if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))continue;String virtual=root.relativize(p).toString().replace('\\','/');if(!virtual.startsWith("articles/")&&!virtual.startsWith("notes/")&&!virtual.startsWith("drafts/")&&!virtual.equals("history.json")&&!virtual.startsWith("history/"))continue;String content=Files.readString(resolve(virtual));String[] lines=content.split("\\R");for(int i=0;i<lines.length&&results.size()<limit;i++)if(lines[i].toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))results.add(Json.object("path",virtual,"line",i+1,"text",NewsClient.clip(lines[i],300)));if(results.size()>=limit)break;}}
        return results;
    }
    public String bash(String command,long deadline)throws Exception{if(!Set.of("pwd","ls").contains(command))throw new IllegalArgumentException("仅接受 pwd 或 ls 字面值");return command.equals("pwd")?"/workspace":Json.stringify(list("."));}
}

