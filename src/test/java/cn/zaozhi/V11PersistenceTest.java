package cn.zaozhi;

import cn.zaozhi.config.SingleInstanceGuard;
import cn.zaozhi.controller.ApiException;
import cn.zaozhi.delivery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class V11PersistenceTest {
    @TempDir Path dir;
    Settings live(){return new Settings(Map.of("DATA_DIR",dir.toString(),"DEMO_MODE","false","DEEPSEEK_API_KEY","test-only","DELIVERY_RETRY_COOLDOWN","0"));}
    String running(Database db,String uid,String mode){String rid=db.claim(uid,null,mode,"manual",null);db.execute("UPDATE runs SET status='running' WHERE id=?",rid);return rid;}
    com.fasterxml.jackson.databind.node.ObjectNode p(){return Profile.validate(Json.object("nickname","fixture","channel","email","email","old@example.com"));}
    com.fasterxml.jackson.databind.node.ObjectNode brief(){return Json.object("title","Fixture","intro","No matching news","items",List.of());}
    @Test void upgradePreservesOldRowsAndMakesConsistentBackup()throws Exception {
        legacy(false);Database db=new Database(live());assertNotNull(db.one("SELECT id FROM subscriptions WHERE id='legacy-sub'"));
        assertEquals(1,db.all("SELECT * FROM briefs").size());assertEquals("unknown",db.one("SELECT status FROM deliveries").path("status").asText());
        assertEquals("OMITTED",db.one("SELECT payload_state FROM events").path("payload_state").asText());
        assertEquals(1,Files.list(dir.resolve("backups")).count());assertTrue(db.all("PRAGMA foreign_key_check").isEmpty());
        assertThrows(IllegalStateException.class,()->db.execute("DELETE FROM subscriptions WHERE id='legacy-sub'"));
        assertNotNull(new Database(live()).one("SELECT id FROM briefs WHERE id='legacy-brief'"));
    }
    @Test void invalidLegacyOwnershipRollsBackWithoutDeletingHistory()throws Exception {
        legacy(true);assertThrows(IllegalStateException.class,()->new Database(live()));
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("news.sqlite3"));Statement s=c.createStatement()) {
            try(ResultSet rs=s.executeQuery("SELECT user_id FROM briefs WHERE id='legacy-brief'")){assertTrue(rs.next());assertEquals("other",rs.getString(1));}
            try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version=2")){assertEquals(0,rs.getInt(1));}
        }
    }
    @Test void changedAppliedChecksumIsRejected(){Database db=new Database(dir);db.execute("UPDATE schema_migrations SET checksum='changed' WHERE version=2");assertThrows(IllegalStateException.class,()->new Database(dir));}
    @Test void compositeForeignKeyAndDemoDeliveryAreEnforced(){Database db=new Database(dir);var user=db.createUser(p());String uid=user.path("id").asText(),rid=running(db,uid,"live");var other=db.createUser(p());
        assertThrows(IllegalStateException.class,()->db.execute("INSERT INTO briefs(id,subscription_id,run_id,title,intro,items,mode,created_at) VALUES (?,?,?,?,?,?,?,?)","bad",other.path("id").asText(),rid,"X","Y","[]","live",Database.now()));
        assertThrows(IllegalStateException.class,()->db.execute("INSERT INTO briefs(id,subscription_id,run_id,title,intro,items,mode,created_at) VALUES (?,?,?,?,?,?,?,?)","bad",uid,rid,"X","Y","[]","demo",Database.now()));
        db.execute("UPDATE runs SET status='failed' WHERE id=?",rid);String demo=running(db,uid,"demo"),bid=db.finish(demo,brief());
        assertThrows(IllegalStateException.class,()->db.execute("INSERT INTO deliveries(id,brief_id,channel,recipient,status,message_id,created_at,updated_at) VALUES (?,?,'email',?,'pending',?,?,?)","d",bid,"x@example.com","m",Database.now(),Database.now()));
    }
    @Test void deliveryInsertFailureRollsBackBriefAndSuccess(){Database db=new Database(dir);var u=db.createUser(p());String rid=running(db,u.path("id").asText(),"live");db.execute("CREATE TRIGGER test_reject_delivery BEFORE INSERT ON deliveries BEGIN SELECT RAISE(ABORT,'fixture'); END");assertThrows(IllegalStateException.class,()->db.finish(rid,brief()));assertTrue(db.all("SELECT * FROM briefs").isEmpty());assertEquals("running",db.one("SELECT status FROM runs WHERE id=?",rid).path("status").asText());}
    @Test void pendingSurvivesRestartWithoutNotificationAndUsesFrozenRecipient(){var settings=live();Database db=new Database(settings);var u=db.createUser(p());String uid=u.path("id").asText(),bid=db.finish(running(db,uid,"live"),brief());
        db.execute("UPDATE subscriptions SET profile=? WHERE id=?",Json.stringify(Profile.validate(Json.object("nickname","changed","channel","email","email","new@example.com"))),uid);db.recover();AtomicInteger sends=new AtomicInteger();var delivery=new DeliveryService(settings,db,(b,p)->{assertEquals("old@example.com",p.path("email").asText());sends.incrementAndGet();});
        try{delivery.dispatchDue();delivery.dispatchDue();assertEquals(1,sends.get());assertEquals("sent",db.brief(uid,bid).path("delivery_status").asText());}finally{delivery.close();}
    }
    @Test void uncertainSendDoesNotAutomaticallyRepeatAndNeedsExplicitConfirmation(){var settings=live();Database db=new Database(settings);var u=db.createUser(p());String uid=u.path("id").asText(),bid=db.finish(running(db,uid,"live"),brief());AtomicInteger sends=new AtomicInteger();var delivery=new DeliveryService(settings,db,(b,p)->{sends.incrementAndGet();throw new java.net.SocketTimeoutException("accepted then connection lost");});
        try{delivery.dispatchDue();db.recover();delivery.dispatchDue();assertEquals(1,sends.get());assertEquals("unknown",db.brief(uid,bid).path("delivery_status").asText());assertEquals(409,assertThrows(ApiException.class,()->delivery.retry(uid,bid)).status);assertEquals(404,assertThrows(ApiException.class,()->delivery.retry("other",bid,true)).status);}finally{delivery.close();}
    }
    @Test void temporaryRejectionBackoffStopsAfterThreeAttempts(){var settings=live();Database db=new Database(settings);var u=db.createUser(p());String bid=db.finish(running(db,u.path("id").asText(),"live"),brief());AtomicInteger sends=new AtomicInteger();var delivery=new DeliveryService(settings,db,(b,p)->{sends.incrementAndGet();throw new MailFailure(MailFailure.Kind.TEMPORARY,"SMTP_451","Rejected before acceptance");});
        try{for(int i=0;i<4;i++){delivery.dispatchDue();db.execute("UPDATE deliveries SET next_attempt_at_ms=0 WHERE next_attempt_at_ms IS NOT NULL");}assertEquals(3,sends.get());assertEquals(3,db.one("SELECT attempt_count FROM deliveries WHERE brief_id=?",bid).path("attempt_count").asInt());}finally{delivery.close();}
    }
    @Test void idempotencyReplaysButRejectsDifferentContentAndFreezesWindow(){Database db=new Database(dir);var u=db.createUser(p());String uid=u.path("id").asText();var a=db.claim(uid,"demo","manual",null,"key","hash-a",null,live());var replay=db.claim(uid,"demo","manual",null,"key","hash-a",null,live());assertTrue(replay.replay());assertEquals(a.id(),replay.id());assertEquals(409,assertThrows(ApiException.class,()->db.claim(uid,"demo","manual",null,"key","hash-b",null,live())).status);var row=db.one("SELECT * FROM runs WHERE id=?",a.id());assertEquals(48*3600000L,row.path("window_end_ms").asLong()-row.path("window_start_ms").asLong());assertFalse(row.path("config_snapshot").asText().contains("test-only"));}
    @Test void singleInstanceLockProtectsSameDirectoryAndCanBeReleased()throws Exception{try(var first=new SingleInstanceGuard(dir)){assertThrows(IllegalStateException.class,()->new SingleInstanceGuard(dir));}try(var next=new SingleInstanceGuard(dir)){assertNotNull(next);}}
    private void legacy(boolean invalid)throws Exception {
        Class.forName("org.sqlite.JDBC");String schema=Files.readString(Path.of("src/main/resources/migrations/001_initial.sql"));
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("news.sqlite3"));Statement s=c.createStatement()) {
            for(String part:schema.split(";"))if(!part.isBlank())s.execute(part);
            s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL)");s.execute("INSERT INTO schema_migrations VALUES(1,'2026-10-10T00:00:00Z')");
            try(var insert=Database.prepare(c,"INSERT INTO subscriptions VALUES(?,?,?,?)","legacy-sub",Database.hash("legacy-token"),Json.stringify(p()),"2026-10-10T00:00:00Z")){insert.executeUpdate();}
            try(var insert=Database.prepare(c,"INSERT INTO subscriptions VALUES(?,?,?,?)","other","other-hash",Json.stringify(p()),"2026-10-10T00:00:00Z")){insert.executeUpdate();}
            try(var insert=Database.prepare(c,"INSERT INTO runs(id,user_id,status,trigger,mode,created_at,finished_at,preferences) VALUES('legacy-run','legacy-sub','succeeded','manual','live','2026-10-10T00:00:00Z','2026-10-10T00:01:00Z',?)",Json.stringify(p()))){insert.executeUpdate();}
            try(var insert=Database.prepare(c,"INSERT INTO briefs(id,user_id,run_id,title,intro,items,mode,created_at,delivery_status) VALUES('legacy-brief',?,'legacy-run','Title','Intro','[]','live','2026-10-10T00:01:00Z','sending')",invalid?"other":"legacy-sub")){insert.executeUpdate();}
            s.execute("INSERT INTO events(run_id,created_at,kind,payload) VALUES('legacy-run','2026-10-10T00:00:00Z','tool','{}')");
        }
    }
}
