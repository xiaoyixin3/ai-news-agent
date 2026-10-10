package cn.zaozhi.workspace;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public final class MaintenanceService {
    private final Database db;
    public MaintenanceService(Database db){this.db=db;}
    @Scheduled(fixedDelay=600000,initialDelay=600000)public void sweep(){cleanup(Instant.now());}
    public void cleanup(Instant now){synchronized(db){
        String workspaceCut=now.minus(Duration.ofDays(7)).toString(),detailCut=now.minus(Duration.ofDays(30)).toString(),metaCut=now.minus(Duration.ofDays(90)).toString(),briefCut=now.minus(Duration.ofDays(180)).toString();
        for(var run:db.all("SELECT id FROM runs WHERE status NOT IN ('queued','running') AND finished_at<?",workspaceCut)){
            String rid=run.path("id").asText();db.execute("UPDATE run_sources SET body_state='EXPIRED',body_ref=NULL WHERE run_id=? AND body_state='AVAILABLE'",rid);deleteTree(db.dataDir.resolve("workspaces").resolve(rid),db.dataDir.resolve("workspaces"));
        }
        for(var event:db.all("SELECT id,payload_ref FROM events WHERE payload_state='EXTERNAL' AND created_at<?",detailCut)){
            String key=event.path("payload_ref").asText();db.execute("UPDATE events SET payload_state='EXPIRED',payload_ref=NULL WHERE id=? AND payload_state='EXTERNAL'",event.path("id").asLong());deleteObject(db.dataDir.resolve("artifacts").resolve(key),db.dataDir.resolve("artifacts"));
        }
        db.execute("UPDATE events SET payload_state='EXPIRED',payload=NULL,payload_ref=NULL WHERE created_at<? AND payload_state='INLINE'",detailCut);
        // Brief lifetime retains its run shell and all retry ancestors. Active deliveries
        // are never discarded while they might be on the remote server.
        for(var brief:db.all("SELECT id FROM briefs WHERE created_at<? AND NOT EXISTS(SELECT 1 FROM deliveries d WHERE d.brief_id=briefs.id AND d.status IN ('pending','sending','unknown'))",briefCut)){
            String id=brief.path("id").asText();db.execute("DELETE FROM deliveries WHERE brief_id=?",id);db.execute("DELETE FROM briefs WHERE id=?",id);
        }
        db.execute("DELETE FROM events WHERE created_at<? AND run_id IN (SELECT id FROM runs WHERE status NOT IN ('queued','running'))",metaCut);
        boolean removed;
        do{removed=false;for(var run:db.all("SELECT id FROM runs r WHERE status NOT IN ('queued','running') AND finished_at<? AND NOT EXISTS(SELECT 1 FROM briefs b WHERE b.run_id=r.id) AND NOT EXISTS(SELECT 1 FROM runs child WHERE child.retry_of=r.id)",metaCut)){
            String id=run.path("id").asText();db.execute("DELETE FROM events WHERE run_id=?",id);db.execute("DELETE FROM run_sources WHERE run_id=?",id);db.execute("DELETE FROM runs WHERE id=?",id);removed=true;
        }}while(removed);
        checkObjects();orphanSweep(now.minus(Duration.ofHours(24)));
    }}
    private void checkObjects(){
        Path artifacts=db.dataDir.resolve("artifacts");
        for(var e:db.all("SELECT id,payload_ref,payload_sha256 FROM events WHERE payload_state='EXTERNAL'"))try{
            Path p=artifacts.resolve(e.path("payload_ref").asText()).normalize();
            if(!p.startsWith(artifacts)||Files.isSymbolicLink(p)||Files.size(p)>65536||!Database.hash(Files.readString(p)).equals(e.path("payload_sha256").asText()))throw new IllegalStateException();
        }catch(Exception missing){db.execute("UPDATE events SET payload_state='MISSING',payload_ref=NULL WHERE id=?",e.path("id").asLong());}
        for(var source:db.all("SELECT run_id,source_id,body_ref FROM run_sources WHERE body_state='AVAILABLE'")){
            Path root=db.dataDir.resolve("workspaces").resolve(source.path("run_id").asText()),p=root.resolve(source.path("body_ref").asText()).normalize();
            if(!p.startsWith(root)||Files.isSymbolicLink(p)||!Files.isRegularFile(p))db.execute("UPDATE run_sources SET body_state='MISSING',body_ref=NULL WHERE run_id=? AND source_id=?",source.path("run_id").asText(),source.path("source_id").asText());
        }
    }
    public void deleteSubscription(String uid){synchronized(db){
        ArrayNode runs=db.all("SELECT id FROM runs WHERE subscription_id=?",uid);
        try(Connection c=db.connect();Statement tx=c.createStatement()){
            tx.execute("BEGIN IMMEDIATE");try{
                if(!Database.query(c,"SELECT id FROM runs WHERE subscription_id=? AND status IN ('queued','running')",uid).isEmpty())throw new cn.zaozhi.controller.ApiException(409,"请先取消正在执行的生成任务");
                if(!Database.query(c,"SELECT d.id FROM deliveries d JOIN briefs b ON b.id=d.brief_id WHERE b.subscription_id=? AND d.status IN ('sending','unknown')",uid).isEmpty())throw new cn.zaozhi.controller.ApiException(409,"邮件正在发送或结果不确定，请先核实投递状态");
                for(String sql:List.of("DELETE FROM deliveries WHERE brief_id IN (SELECT id FROM briefs WHERE subscription_id=?)","DELETE FROM briefs WHERE subscription_id=?","DELETE FROM events WHERE run_id IN (SELECT id FROM runs WHERE subscription_id=?)","DELETE FROM run_sources WHERE run_id IN (SELECT id FROM runs WHERE subscription_id=?)"))try(var s=Database.prepare(c,sql,uid)){s.executeUpdate();}
                while(!Database.query(c,"SELECT id FROM runs WHERE subscription_id=?",uid).isEmpty()){
                    var leaves=Database.query(c,"SELECT id FROM runs r WHERE subscription_id=? AND NOT EXISTS(SELECT 1 FROM runs child WHERE child.retry_of=r.id)",uid);if(leaves.isEmpty())throw new IllegalStateException("重试关系存在循环");
                    for(var leaf:leaves)try(var s=Database.prepare(c,"DELETE FROM runs WHERE id=? AND subscription_id=?",leaf.path("id").asText(),uid)){s.executeUpdate();}
                }
                try(var s=Database.prepare(c,"DELETE FROM subscriptions WHERE id=?",uid)){if(s.executeUpdate()!=1)throw new cn.zaozhi.controller.ApiException(404,"订阅不存在");}tx.execute("COMMIT");
            }catch(Exception e){tx.execute("ROLLBACK");throw e;}
        }catch(cn.zaozhi.controller.ApiException e){throw e;}catch(Exception e){throw new IllegalStateException("删除订阅失败",e);}
        for(var run:runs){String id=run.path("id").asText();deleteTree(db.dataDir.resolve("workspaces").resolve(id),db.dataDir.resolve("workspaces"));deleteTree(db.dataDir.resolve("artifacts").resolve(id),db.dataDir.resolve("artifacts"));}
    }}
    private void orphanSweep(Instant cutoff){Path root=db.dataDir.resolve("artifacts");if(!Files.isDirectory(root))return;Set<String> refs=new HashSet<>();for(var e:db.all("SELECT payload_ref FROM events WHERE payload_state='EXTERNAL'"))refs.add(e.path("payload_ref").asText().replace('\\','/'));
        try(var paths=Files.walk(root)){for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){String key=root.relativize(file).toString().replace('\\','/');if(!refs.contains(key)&&Files.getLastModifiedTime(file).toInstant().isBefore(cutoff))deleteObject(file,root);}}catch(Exception e){throw new IllegalStateException("孤儿对象清理失败",e);}
    }
    public ObjectNode backup(Path destination)throws Exception{synchronized(db){
        Path dest=destination.toAbsolutePath().normalize();if(Files.exists(dest))throw new IllegalArgumentException("备份目标必须是新目录");Files.createDirectories(dest);Path snapshot=dest.resolve("news.sqlite3");
        try(Connection c=db.connect();var s=Database.prepare(c,"VACUUM INTO ?",snapshot.toString())){s.execute();}
        ArrayNode manifest=Json.arr();manifest.add(entry(dest,snapshot));
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+snapshot)){
            for(var row:Database.query(c,"SELECT payload_ref,payload_sha256 FROM events WHERE payload_state='EXTERNAL'")){String key=row.path("payload_ref").asText();Path source=db.dataDir.resolve("artifacts").resolve(key);if(!Files.exists(source)||!Database.hash(Files.readString(source)).equals(row.path("payload_sha256").asText()))throw new IllegalStateException("备份发现缺失或损坏审计对象");copy(dest,source,Path.of("artifacts").resolve(key),manifest);}
            for(var row:Database.query(c,"SELECT run_id,body_ref FROM run_sources WHERE body_state='AVAILABLE'")){String run=row.path("run_id").asText(),body=row.path("body_ref").asText();copy(dest,db.dataDir.resolve("workspaces").resolve(run).resolve(body),Path.of("workspaces").resolve(run).resolve(body),manifest);}
        }
        ObjectNode result=Json.object("format","ai-news-backup-1.1","created_at",Database.now(),"files",manifest);Files.writeString(dest.resolve("manifest.json"),Json.stringify(result),StandardCharsets.UTF_8);return result;
    }}
    private static String fileHash(Path p)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(p)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private ObjectNode entry(Path dest,Path p)throws Exception{return Json.object("path",dest.relativize(p).toString().replace('\\','/'),"bytes",Files.size(p),"sha256",fileHash(p));}
    private void copy(Path dest,Path source,Path key,ArrayNode manifest)throws Exception{Path origin=source.toAbsolutePath().normalize(),target=dest.resolve(key).normalize();if(!origin.startsWith(db.dataDir)||!target.startsWith(dest)||Files.isSymbolicLink(origin))throw new IllegalArgumentException("备份对象路径无效");Files.createDirectories(target.getParent());Files.copy(origin,target);manifest.add(entry(dest,target));}
    public static void restore(Path backup,Path destination)throws Exception {
        backup=backup.toAbsolutePath().normalize();destination=destination.toAbsolutePath().normalize();
        if(Files.exists(destination))throw new IllegalArgumentException("恢复目标必须不存在，避免覆盖当前数据");JsonNode manifest=Json.parse(Files.readString(backup.resolve("manifest.json")));if(!manifest.path("format").asText().equals("ai-news-backup-1.1"))throw new IllegalArgumentException("备份格式无效");
        for(JsonNode item:manifest.path("files")){Path file=backup.resolve(item.path("path").asText()).normalize();if(!file.startsWith(backup.toAbsolutePath().normalize())||Files.isSymbolicLink(file)||!Files.exists(file))throw new IllegalArgumentException("备份对象缺失或越界");if(!fileHash(file).equals(item.path("sha256").asText()))throw new IllegalStateException("备份对象校验失败");}
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+backup.resolve("news.sqlite3"));Statement s=c.createStatement()) {try(ResultSet rs=s.executeQuery("PRAGMA integrity_check")){if(!rs.next()||!rs.getString(1).equals("ok"))throw new IllegalStateException("备份数据库完整性失败");}try(ResultSet rs=s.executeQuery("PRAGMA foreign_key_check")){if(rs.next())throw new IllegalStateException("备份数据库外键失败");}}
        Path parent=destination.getParent();Files.createDirectories(parent);Path staging=Files.createTempDirectory(parent,".restore-");try{for(JsonNode item:manifest.path("files")){Path key=Path.of(item.path("path").asText()),target=staging.resolve(key).normalize();if(!target.startsWith(staging))throw new IllegalArgumentException("恢复路径越界");Files.createDirectories(target.getParent());Files.copy(backup.resolve(key),target);}Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE);}finally{if(Files.exists(staging))deleteTree(staging,parent);}
    }
    public void deleteBrief(String uid,String bid){synchronized(db){db.brief(uid,bid);var active=db.one("SELECT id FROM deliveries WHERE brief_id=? AND status IN ('sending','unknown')",bid);if(active!=null)throw new cn.zaozhi.controller.ApiException(409,"发送中或结果不确定的邮件请先核实，不能删除");db.execute("DELETE FROM deliveries WHERE brief_id=?",bid);db.execute("DELETE FROM briefs WHERE id=? AND subscription_id=?",bid,uid);}}
    private static void deleteObject(Path path,Path root){try{Path absolute=path.toAbsolutePath().normalize(),base=root.toAbsolutePath().normalize();if(!absolute.startsWith(base)||absolute.equals(base)||Files.isSymbolicLink(absolute))throw new IllegalArgumentException("清理路径越界");Files.deleteIfExists(absolute);}catch(Exception e){throw new IllegalStateException("文件清理失败",e);}}
    private static void deleteTree(Path path,Path root){if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;try{Path absolute=path.toAbsolutePath().normalize(),base=root.toAbsolutePath().normalize();if(!absolute.startsWith(base)||absolute.equals(base)||!absolute.toRealPath().startsWith(base.toRealPath()))throw new IllegalArgumentException("清理路径越界");try(var paths=Files.walk(absolute)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}catch(Exception e){throw new IllegalStateException("工作区清理失败",e);}}
}
