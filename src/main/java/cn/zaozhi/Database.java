package cn.zaozhi;

import cn.zaozhi.controller.ApiException;
import cn.zaozhi.repository.MigrationRunner;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class Database {
    private final String url;
    public final Path dataDir;
    private final Settings settings;
    public Database(Path dir) {this(new Settings(Map.of("DATA_DIR",dir.toString())));}
    public Database(Settings settings) {
        this.settings=settings;dataDir=settings.dataDir;
        try {
            Files.createDirectories(dataDir);Class.forName("org.sqlite.JDBC");url="jdbc:sqlite:"+dataDir.resolve("news.sqlite3");
            try(Connection c=connect()){MigrationRunner.migrate(c,dataDir);}
        }catch(Exception e){throw new IllegalStateException("数据库初始化失败："+e.getMessage(),e);}
    }
    public Connection connect()throws SQLException {
        Connection c=DriverManager.getConnection(url);
        try(Statement s=c.createStatement()) {s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");try(ResultSet rs=s.executeQuery("PRAGMA foreign_keys")){if(!rs.next()||rs.getInt(1)!=1)throw new SQLException("外键未启用");}}return c;
    }
    public static PreparedStatement prepare(Connection c,String sql,Object... args)throws SQLException {PreparedStatement s=c.prepareStatement(sql);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
    public static ArrayNode query(Connection c,String sql,Object... args)throws SQLException {
        ArrayNode result=Json.arr();try(var s=prepare(c,sql,args);var rs=s.executeQuery()){var m=rs.getMetaData();while(rs.next()){ObjectNode row=Json.obj();for(int i=1;i<=m.getColumnCount();i++)row.set(m.getColumnLabel(i),Json.tree(rs.getObject(i)));result.add(row);}}return result;
    }
    public synchronized int execute(String sql,Object... args){try(Connection c=connect();var s=prepare(c,sql,args)){return s.executeUpdate();}catch(SQLException e){throw new IllegalStateException("数据库写入失败",e);}}
    public synchronized ArrayNode all(String sql,Object... args){try(Connection c=connect()){return query(c,sql,args);}catch(SQLException e){throw new IllegalStateException("数据库读取失败",e);}}
    public ObjectNode one(String sql,Object... args){var a=all(sql,args);return a.isEmpty()?null:(ObjectNode)a.get(0);}
    public static String id(){return UUID.randomUUID().toString().replace("-","");}
    public static String now(){return Instant.now().toString();}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public ObjectNode createUser(JsonNode profile){byte[] b=new byte[32];new SecureRandom().nextBytes(b);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(b),uid=id();execute("INSERT INTO subscriptions(id,token_hash,profile,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?)",uid,hash(token),Json.stringify(profile),profile.path("enabled").asBoolean()?1:0,now(),now());return Json.object("id",uid,"token",token,"profile",profile);}
    public ObjectNode authenticate(String token){return one("SELECT * FROM subscriptions WHERE token_hash=?",hash(token));}
    public record Claim(String id,boolean replay){}
    public String claim(String uid,JsonNode p,String mode,String trigger,String date){try{return claim(uid,mode,trigger,date,null,null,null,settings).id();}catch(ApiException e){if(e.status==409)return null;throw e;}}
    public synchronized Claim claim(String uid,String mode,String trigger,String date,String key,String requestHash,String retryOf,Settings runtime) {
        if(key!=null&&(key.isBlank()||key.length()>128||!key.matches("[A-Za-z0-9._:-]+")))throw new ApiException(400,"幂等键格式无效");
        try(Connection c=connect();Statement tx=c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                if(key!=null){var old=query(c,"SELECT id,request_hash FROM runs WHERE subscription_id=? AND idempotency_key=?",uid,key);if(!old.isEmpty()){if(!old.get(0).path("request_hash").asText().equals(requestHash))throw new ApiException(409,"同一幂等键对应不同请求");tx.execute("COMMIT");return new Claim(old.get(0).path("id").asText(),true);}}
                if(!query(c,"SELECT id FROM runs WHERE subscription_id=? AND status IN ('queued','running')",uid).isEmpty())throw new ApiException(409,"已有生成任务正在执行");
                if(query(c,"SELECT COUNT(*) n FROM runs WHERE status IN ('queued','running')").get(0).path("n").asInt()>=34)throw new ApiException(429,"生成队列已满");
                var user=query(c,"SELECT profile FROM subscriptions WHERE id=?",uid);if(user.isEmpty())throw new ApiException(404,"订阅不存在");JsonNode snapshot=Json.parse(user.get(0).path("profile").asText());
                if(retryOf!=null&&query(c,"SELECT id FROM runs WHERE id=? AND subscription_id=? AND mode=? AND status IN ('failed','cancelled')",retryOf,uid,mode).isEmpty())throw new ApiException(409,"只能重试本订阅失败或取消的任务");
                long end=System.currentTimeMillis();String rid=id();
                try(var s=prepare(c,"INSERT INTO runs(id,subscription_id,status,trigger,schedule_date,mode,created_at,preferences,delivery_snapshot,config_snapshot,deadline_at_ms,window_start_ms,window_end_ms,idempotency_key,request_hash,retry_of) VALUES (?,?,'queued',?,?,?,?,?,?,?,?,?,?,?,?,?)",rid,uid,trigger,date,mode,now(),Json.stringify(snapshot),Json.stringify(Json.object("channel",snapshot.path("channel"),"email",snapshot.path("email"))),Json.stringify(runtime.snapshot()),end+runtime.runTimeout*1000L,end-snapshot.path("lookback_hours").asInt()*3600000L,end,key,requestHash,retryOf)){s.executeUpdate();}
                tx.execute("COMMIT");return new Claim(rid,false);
            }catch(Exception e){tx.execute("ROLLBACK");throw e;}
        }catch(ApiException e){throw e;}catch(SQLException e){if(e.getErrorCode()==19)throw new ApiException(409,"任务归属、每日领取或幂等约束冲突");throw new IllegalStateException("无法创建任务",e);}
    }
    public synchronized void event(String rid,String kind,JsonNode payload) {
        String safe=settings.redact(Json.stringify(payload));byte[] bytes=safe.getBytes(StandardCharsets.UTF_8);String inline=bytes.length<=4096?safe:null,ref=null,sha=null,state=inline!=null?"INLINE":"OMITTED";
        var totals=one("SELECT COUNT(*) n,COALESCE(SUM(payload_bytes),0) bytes FROM events WHERE run_id=?",rid);int count=totals.path("n").asInt();boolean terminal=Set.of("error","terminal").contains(kind);if(count>=(terminal?256:254))return;
        if(bytes.length>4096&&bytes.length<=65536&&totals.path("bytes").asLong()+bytes.length<=2*1024*1024) {
            try{String object=id()+".json";Path dir=dataDir.resolve("artifacts").resolve(rid);Files.createDirectories(dir);Path temp=dir.resolve(object+".tmp"),dest=dir.resolve(object);Files.write(temp,bytes,StandardOpenOption.CREATE_NEW);sha=hash(safe);if(!hash(Files.readString(temp)).equals(sha))throw new IllegalStateException("审计对象校验失败");Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE);ref=rid+"/"+object;state="EXTERNAL";}catch(Exception e){throw new IllegalStateException("审计详情保存失败",e);}
        }
        String summary=NewsClient.clip(payload.path("message").asText(payload.path("name").asText(kind)),512);int recorded=state.equals("OMITTED")?0:bytes.length;
        execute("INSERT INTO events(run_id,created_at,seq,kind,step_id,tool_name,status,duration_ms,error_code,summary,payload_bytes,payload,payload_ref,payload_sha256,payload_state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",rid,now(),count+1,kind,payload.path("step_id").asText(null),payload.path("name").asText(null),payload.path("status").asText(null),payload.path("elapsed_ms").isNumber()?payload.path("elapsed_ms").asLong():null,payload.path("error_code").asText(null),settings.redact(summary),recorded,inline,ref,sha,state);
    }
    public ArrayNode events(String uid,String rid) {
        if(one("SELECT id FROM runs WHERE id=? AND subscription_id=?",rid,uid)==null)throw new ApiException(404,"记录不存在");var rows=all("SELECT e.* FROM events e JOIN runs r ON r.id=e.run_id WHERE e.run_id=? AND r.subscription_id=? ORDER BY e.seq",rid,uid);
        for(JsonNode entry:rows){ObjectNode row=(ObjectNode)entry;JsonNode detail=Json.object("message",row.path("summary"),"truncated",true);
            if(row.path("payload_state").asText().equals("INLINE"))detail=Json.parse(row.path("payload").asText());
            else if(row.path("payload_state").asText().equals("EXTERNAL")){try{Path path=dataDir.resolve("artifacts").resolve(row.path("payload_ref").asText()).normalize();if(!path.startsWith(dataDir.resolve("artifacts"))||Files.isSymbolicLink(path)||Files.size(path)>65536)throw new IllegalStateException();String content=Files.readString(path);if(!hash(content).equals(row.path("payload_sha256").asText()))throw new IllegalStateException();detail=Json.parse(content);}catch(Exception e){row.put("payload_state","MISSING");execute("UPDATE events SET payload_state='MISSING',payload_ref=NULL WHERE id=?",row.path("id").asLong());}}
            row.set("payload",detail);row.remove(List.of("payload_ref","payload_sha256"));
        }return rows;
    }
    public static final String BRIEF_SELECT="SELECT b.*,CASE WHEN b.mode='demo' THEN 'demo_inbox' ELSE COALESCE(d.status,'inbox') END delivery_status,d.error_summary delivery_error,d.recipient delivery_recipient,d.attempt_count delivery_attempts,d.id delivery_id FROM briefs b LEFT JOIN deliveries d ON d.brief_id=b.id ";
    public ArrayNode history(String uid,int limit){return history(uid,null,limit,0);}
    public ArrayNode history(String uid,int limit,int offset){return history(uid,null,limit,offset);}
    public ArrayNode history(String uid,String mode,int limit,int offset){var rows=all(BRIEF_SELECT+"WHERE b.subscription_id=? "+(mode==null?"":"AND b.mode=? ")+"ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?",mode==null?new Object[]{uid,limit,offset}:new Object[]{uid,mode,limit,offset});for(JsonNode row:rows)((ObjectNode)row).set("items",Json.parse(row.path("items").asText()));return rows;}
    public ObjectNode brief(String uid,String bid){var row=one(BRIEF_SELECT+"WHERE b.id=? AND b.subscription_id=?",bid,uid);if(row==null)throw new ApiException(404,"简报不存在");row.set("items",Json.parse(row.path("items").asText()));return row;}
    public synchronized String finish(String rid,JsonNode brief) {
        try(Connection c=connect();Statement tx=c.createStatement()){tx.execute("BEGIN IMMEDIATE");try{
            var rows=query(c,"SELECT * FROM runs WHERE id=? AND status='running' AND deadline_at_ms>?",rid,System.currentTimeMillis());if(rows.isEmpty())throw new IllegalStateException("任务终态、取消或期限不允许提交");JsonNode run=rows.get(0);String bid=id();
            try(var s=prepare(c,"INSERT INTO briefs(id,subscription_id,run_id,title,intro,items,mode,created_at,empty_brief) VALUES (?,?,?,?,?,?,?,?,?)",bid,run.path("subscription_id").asText(),rid,brief.path("title").asText(),brief.path("intro").asText(),Json.stringify(brief.path("items")),run.path("mode").asText(),now(),brief.path("items").isEmpty()?1:0)){s.executeUpdate();}
            JsonNode delivery=Json.parse(run.path("delivery_snapshot").asText());if(run.path("mode").asText().equals("live")&&delivery.path("channel").asText().equals("email")){try(var s=prepare(c,"INSERT INTO deliveries(id,brief_id,channel,recipient,status,message_id,created_at,updated_at) VALUES (?,?,'email',?,'pending',?,?,?)",id(),bid,delivery.path("email").asText(),"<brief-"+bid+"@daily-ai-news.local>",now(),now())){s.executeUpdate();}}
            try(var s=prepare(c,"UPDATE runs SET status='succeeded',phase=NULL,finished_at=? WHERE id=? AND status='running'",now(),rid)){if(s.executeUpdate()!=1)throw new IllegalStateException("提交状态冲突");}tx.execute("COMMIT");return bid;
        }catch(Exception e){tx.execute("ROLLBACK");throw e;}}catch(Exception e){throw new IllegalStateException("保存简报失败",e);}
    }
    public void recover(){execute("UPDATE runs SET status='failed',phase=NULL,finished_at=?,error_code='PROCESS_RESTARTED',error='服务重启，任务中断；可重试生成' WHERE status IN ('queued','running')",now());execute("UPDATE deliveries SET status='unknown',updated_at=?,error_code='PROCESS_RESTARTED',error_summary='发送结果不确定，请先确认是否已收信' WHERE status='sending'",now());}
}
