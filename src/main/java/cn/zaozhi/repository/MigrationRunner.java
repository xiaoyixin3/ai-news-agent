package cn.zaozhi.repository;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Applied migrations are immutable. Existing data is never silently discarded. */
public final class MigrationRunner {
    public static void migrate(Connection c,Path data) throws Exception {
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL,checksum TEXT)");
            boolean checksum=false;
            try(ResultSet rs=s.executeQuery("PRAGMA table_info(schema_migrations)")) {while(rs.next()) if(rs.getString("name").equals("checksum"))checksum=true;}
            if(!checksum)s.execute("ALTER TABLE schema_migrations ADD COLUMN checksum TEXT");
        }
        for(int version=1;version<=2;version++) {
            String name=version==1?"001_initial.sql":"002_runtime.sql";
            String sql;
            try(var in=MigrationRunner.class.getResourceAsStream("/migrations/"+name)) {sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
            String hash=Database.hash(sql);
            try(PreparedStatement check=c.prepareStatement("SELECT checksum FROM schema_migrations WHERE version=?")) {
                check.setInt(1,version);
                try(ResultSet rs=check.executeQuery()) {
                    if(rs.next()) {
                        String old=rs.getString(1);
                        if(old!=null&&!old.equals(hash))throw new IllegalStateException("已执行 migration 被修改："+name);
                        if(old==null) {try(var adopt=c.prepareStatement("UPDATE schema_migrations SET checksum=? WHERE version=?")) {adopt.setString(1,hash);adopt.setInt(2,version);adopt.executeUpdate();}}
                        continue;
                    }
                }
            }
            if(version==2) {
                Files.createDirectories(data.resolve("backups"));
                try(var backup=c.prepareStatement("VACUUM INTO ?")) {
                    backup.setString(1,data.resolve("backups/pre-v1.1-"+Database.id()+".sqlite3").toString());backup.execute();
                }
            }
            try(Statement s=c.createStatement()) {s.execute("PRAGMA foreign_keys=OFF");s.execute("BEGIN IMMEDIATE");}
            try {
                if(version==2) {try(Statement s=c.createStatement()) {for(String table:List.of("briefs","events","runs","subscriptions"))s.execute("ALTER TABLE "+table+" RENAME TO "+table+"_old");}}
                executeScript(c,sql);
                if(version==2) copyOld(c);
                try(PreparedStatement stamp=c.prepareStatement("INSERT INTO schema_migrations(version,applied_at,checksum) VALUES (?,?,?)")) {stamp.setInt(1,version);stamp.setString(2,Database.now());stamp.setString(3,hash);stamp.executeUpdate();}
                try(Statement s=c.createStatement();ResultSet bad=s.executeQuery("PRAGMA foreign_key_check")) {if(bad.next())throw new IllegalStateException("迁移存在孤儿或错误归属，请检查备份与异常记录");}
                try(Statement s=c.createStatement()) {s.execute("COMMIT");}
            } catch(Exception e) {try(Statement s=c.createStatement()){s.execute("ROLLBACK");}throw new IllegalStateException("迁移 "+name+" 失败，原库已回滚；请检查备份和原因",e);}
            finally {try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}}
        }
    }
    private static void executeScript(Connection c,String sql)throws Exception {
        String clean=sql.replaceAll("(?m)^--.*$","");
        try(Statement s=c.createStatement()) {for(String part:clean.split(";")) if(!part.isBlank()&&!part.strip().startsWith("PRAGMA"))s.execute(part);}
    }
    private static void copyOld(Connection c)throws Exception {
        try(Statement s=c.createStatement()) {s.executeUpdate("INSERT INTO subscriptions(id,token_hash,profile,created_at,updated_at) SELECT id,token_hash,profile,created_at,created_at FROM subscriptions_old");}
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM runs_old")) {
            while(rs.next()) {
                JsonNode p=Json.parse(rs.getString("preferences"));long end=Instant.parse(rs.getString("created_at")).toEpochMilli();
                try(var insert=c.prepareStatement("INSERT INTO runs(id,subscription_id,status,trigger,schedule_date,mode,created_at,finished_at,error,preferences,delivery_snapshot,config_snapshot,deadline_at_ms,window_start_ms,window_end_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    Object[] a={rs.getString("id"),rs.getString("user_id"),rs.getString("status"),rs.getString("trigger"),rs.getString("schedule_date"),rs.getString("mode"),rs.getString("created_at"),rs.getString("finished_at"),rs.getString("error"),rs.getString("preferences"),Json.stringify(Json.object("channel",p.path("channel"),"email",p.path("email"))),"{\"legacy\":true}",end+300000,end-p.path("lookback_hours").asInt(48)*3600000L,end};
                    for(int i=0;i<a.length;i++)insert.setObject(i+1,a[i]);insert.executeUpdate();
                }
            }
        }
        try(Statement s=c.createStatement()) {s.executeUpdate("INSERT INTO briefs(id,subscription_id,run_id,title,intro,items,mode,created_at,empty_brief) SELECT id,user_id,run_id,title,intro,items,mode,created_at,CASE WHEN items='[]' THEN 1 ELSE 0 END FROM briefs_old");}
        Map<String,Integer> seqs=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM events_old ORDER BY id")) {
            while(rs.next()) {
                String rid=rs.getString("run_id"),kind=rs.getString("kind");
                // Legacy trace contained recipient details: retain metadata, quarantine raw
                // bytes in the consistent backup rather than expose unreviewed old payloads.
                try(var insert=c.prepareStatement("INSERT INTO events(id,run_id,created_at,seq,kind,summary,payload_bytes,payload_state) VALUES (?,?,?,?,?,?,0,'OMITTED')")) {
                    insert.setLong(1,rs.getLong("id"));insert.setString(2,rid);insert.setString(3,rs.getString("created_at"));insert.setInt(4,seqs.merge(rid,1,Integer::sum));insert.setString(5,kind);insert.setString(6,"旧版本事件，原始详情保留于升级前备份");insert.executeUpdate();
                }
            }
        }
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT b.*,r.preferences FROM briefs_old b JOIN runs_old r ON r.id=b.run_id")) {
            while(rs.next()) {
                JsonNode p=Json.parse(rs.getString("preferences"));
                if(!rs.getString("mode").equals("live")||!p.path("channel").asText().equals("email"))continue;
                String old=rs.getString("delivery_status");String state=switch(old){case "sent"->"sent";case "sending"->"unknown";case "failed"->"failed";default->"pending";};
                try(var insert=c.prepareStatement("INSERT INTO deliveries(id,brief_id,channel,recipient,status,message_id,attempt_count,created_at,updated_at,sent_at,error_code,error_summary) VALUES (?,?,'email',?,?,?,?,?,?,?,?,?)")) {
                    String bid=rs.getString("id");Object[] a={Database.id(),bid,p.path("email").asText(),state,"<brief-"+bid+"@daily-ai-news.local>",state.equals("pending")?0:1,rs.getString("created_at"),Database.now(),state.equals("sent")?rs.getString("created_at"):null,state.equals("unknown")?"LEGACY_UNCERTAIN":null,state.equals("unknown")?"原发送结果不确定，请确认是否已收信":null};
                    for(int i=0;i<a.length;i++)insert.setObject(i+1,a[i]);insert.executeUpdate();
                }
            }
        }
        try(Statement s=c.createStatement()) {
            for(String table:List.of("briefs_old","events_old","runs_old","subscriptions_old"))s.execute("DROP TABLE "+table);
            s.execute("CREATE TRIGGER deliveries_live_insert BEFORE INSERT ON deliveries WHEN (SELECT mode FROM briefs WHERE id=NEW.brief_id)='demo' BEGIN SELECT RAISE(ABORT,'DEMO_EXTERNAL_DELIVERY_FORBIDDEN'); END");
            s.execute("CREATE TRIGGER deliveries_live_update BEFORE UPDATE OF brief_id ON deliveries WHEN (SELECT mode FROM briefs WHERE id=NEW.brief_id)='demo' BEGIN SELECT RAISE(ABORT,'DEMO_EXTERNAL_DELIVERY_FORBIDDEN'); END");
            s.execute("PRAGMA user_version=2");
        }
    }
}
