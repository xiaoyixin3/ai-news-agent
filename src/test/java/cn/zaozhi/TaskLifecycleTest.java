package cn.zaozhi;

import cn.zaozhi.agent.*;
import cn.zaozhi.delivery.*;
import cn.zaozhi.scheduler.DailyScheduler;
import cn.zaozhi.controller.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleTest {
    @TempDir Path dir;
    static void waitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!done.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(done.getAsBoolean(),"等待异步状态超时");
    }
    @Test void schedulerClaimsDailyOnceAndManualRunKeepsItsOwnKey() throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString())); var db=new Database(dir);
        var profile=Profile.validate(Json.object("nickname","每日订阅","time","09:00")); var u=db.createUser(profile);
        try(varHarness harness=new varHarness(settings,db,(b,p)->{})) {
            DailyScheduler scheduler=new DailyScheduler(settings,db,harness.tasks);
            scheduler.scan(Instant.parse("2026-10-10T00:59:59Z")); assertTrue(db.all("SELECT * FROM runs").isEmpty());
            for(int i=0;i<4;i++) scheduler.scan(Instant.parse("2026-10-10T01:00:00Z"));
            waitUntil(()->db.one("SELECT COUNT(*) AS n FROM runs WHERE status='succeeded'").path("n").asInt()==1);
            scheduler.scan(Instant.parse("2026-10-10T01:01:00Z"));
            assertEquals(1,db.all("SELECT * FROM runs").size());
            harness.tasks.submit(u.path("id").asText(),profile,"manual",null);
            waitUntil(()->db.one("SELECT COUNT(*) AS n FROM runs WHERE status='succeeded'").path("n").asInt()==2);
            assertEquals(1,db.all("SELECT * FROM runs WHERE schedule_date IS NOT NULL").size());
            assertEquals(2,db.history(u.path("id").asText(),30).size());
        }
    }
    @Test void concurrentGenerationAndQueueCapacityAreBounded() throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString())); var db=new Database(dir);
        var delivery=new DeliveryService(settings,db,(b,p)->{});
        CountDownLatch held=new CountDownLatch(1);
        var tasks=new TaskService(settings,db,delivery,p->(m,t,d)->{ held.await(8,TimeUnit.SECONDS); return Json.object("role","assistant","content","test"); });
        try {
            var profile=Profile.validate(Json.object("nickname","排队用户"));
            var u=db.createUser(profile); tasks.submit(u.path("id").asText(),profile,"manual",null);
            ApiException duplicate=assertThrows(ApiException.class,()->tasks.submit(u.path("id").asText(),profile,"manual",null)); assertEquals(409,duplicate.status);
            for(int i=0;i<33;i++) { var next=db.createUser(profile); tasks.submit(next.path("id").asText(),profile,"manual",null); }
            var extra=db.createUser(profile);
            assertEquals(429,assertThrows(ApiException.class,()->tasks.submit(extra.path("id").asText(),profile,"manual",null)).status);
            assertEquals(34,db.all("SELECT * FROM runs").size());
            assertEquals(0,tasks.availableCapacity());
        } finally { held.countDown(); tasks.close(); delivery.close(); }
    }
    @Test void restartRecoveryAndSuccessTransactionCannotLeavePartialBrief() {
        var db=new Database(dir); var p=Profile.validate(Json.object("nickname","恢复测试")); var u=db.createUser(p);
        var rid=db.claim(u.path("id").asText(),p,"demo","manual",null);
        assertThrows(IllegalStateException.class,()->db.finish(rid,Json.object("title","invalid","intro","x","items",List.of())));
        assertTrue(db.history(u.path("id").asText(),30).isEmpty());
        db.recover(); assertEquals("failed",db.one("SELECT status FROM runs WHERE id=?",rid).path("status").asText());
        assertNotNull(db.claim(u.path("id").asText(),p,"demo","manual",null));
    }
    @Test void timeoutFailsAndMissingLiveKeyNeverFallsBackToDemo() throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString(),"RUN_TIMEOUT","1")); var db=new Database(dir);
        var p=Profile.validate(Json.object("nickname","超时测试")); var u=db.createUser(p);
        var delivery=new DeliveryService(settings,db,(b,profile)->{});
        var tasks=new TaskService(settings,db,delivery,profile->(m,t,d)->{Thread.sleep(4000); return Json.object("role","assistant","content","late");});
        try { var rid=tasks.submit(u.path("id").asText(),p,"manual",null); waitUntil(()->db.one("SELECT status FROM runs WHERE id=?",rid).path("status").asText().equals("failed")); assertTrue(db.history(u.path("id").asText(),30).isEmpty()); }
        finally { tasks.close(); delivery.close(); }
        var live=new Settings(Map.of("DATA_DIR",dir.toString(),"DEMO_MODE","false"));
        try(varHarness harness=new varHarness(live,db,(b,profile)->{})) { assertEquals(503,assertThrows(ApiException.class,()->harness.tasks.submit(u.path("id").asText(),p,"manual",null)).status); }
    }
    @Test void mailFailureKeepsBriefAndRetryIsClaimedOnce() throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString(),"DEMO_MODE","false","DEEPSEEK_API_KEY","fixture-key")); var db=new Database(dir);
        var p=Profile.validate(Json.object("nickname","邮件测试","channel","email","email","test@example.com")); var u=db.createUser(p);
        var rid=db.claim(u.path("id").asText(),p,"live","manual",null); db.execute("UPDATE runs SET status='running' WHERE id=?",rid);
        var bid=db.finish(rid,Json.object("title","测试","intro","无新闻","items",List.of()));
        AtomicInteger sends=new AtomicInteger(); CountDownLatch release=new CountDownLatch(1);
        var delivery=new DeliveryService(settings,db,(b,profile)->{if(sends.incrementAndGet()==1) throw new IllegalStateException("temporary mail error fixture-key"); release.await(5,TimeUnit.SECONDS);});
        try {
            delivery.deliver(bid,p); assertEquals("failed",db.one("SELECT delivery_status FROM briefs WHERE id=?",bid).path("delivery_status").asText());
            assertEquals("succeeded",db.one("SELECT status FROM runs WHERE id=?",rid).path("status").asText());
            assertFalse(db.one("SELECT delivery_error FROM briefs WHERE id=?",bid).path("delivery_error").asText().contains("fixture-key"));
            assertEquals(404,assertThrows(ApiException.class,()->delivery.retry("another-user",bid)).status);
            delivery.retry(u.path("id").asText(),bid);
            assertEquals(409,assertThrows(ApiException.class,()->delivery.retry(u.path("id").asText(),bid)).status);
            release.countDown(); waitUntil(()->db.one("SELECT delivery_status FROM briefs WHERE id=?",bid).path("delivery_status").asText().equals("sent"));
            assertEquals(2,sends.get());
        } finally { release.countDown(); delivery.close(); }
    }
    private static class varHarness implements AutoCloseable {
        final DeliveryService delivery; final TaskService tasks;
        varHarness(Settings settings,Database db,MailGateway mail) { delivery=new DeliveryService(settings,db,mail); tasks=new TaskService(settings,db,delivery); }
        public void close() { tasks.close(); delivery.close(); }
    }
}
