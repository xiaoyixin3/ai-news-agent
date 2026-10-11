package cn.zaozhi;

import cn.zaozhi.agent.*;
import cn.zaozhi.controller.ApiException;
import cn.zaozhi.delivery.DeliveryService;
import cn.zaozhi.scheduler.DailyScheduler;
import cn.zaozhi.subscription.SubscriptionService;
import cn.zaozhi.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CoreAcceptanceTest {
    @TempDir Path dir;

    @Test void clockAcrossDaysDeduplicatesAndRestartDoesNotReclaimFinishedDay()throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString()));var db=new Database(settings);var profile=Profile.validate(Json.object("nickname","clock","time","09:00"));var user=db.createUser(profile);
        var delivery=new DeliveryService(settings,db,(b,p)->{});var tasks=new TaskService(settings,db,delivery,DemoClient::new);
        try{
            var scheduler=new DailyScheduler(settings,db,tasks,Clock.fixed(Instant.parse("2026-10-10T00:59:59Z"),ZoneOffset.UTC));scheduler.scan();assertTrue(db.all("SELECT * FROM runs").isEmpty());
            scheduler=new DailyScheduler(settings,db,tasks,Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"),ZoneOffset.UTC));scheduler.scan();awaitRuns(db,1);scheduler.scan();assertEquals(1,db.all("SELECT * FROM runs").size());
            scheduler=new DailyScheduler(settings,db,tasks,Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"),ZoneOffset.UTC));scheduler.scan();awaitRuns(db,2);scheduler.scan();assertEquals(2,db.all("SELECT * FROM runs").size());
        }finally{tasks.close();delivery.close();}
        var reopened=new Database(settings);var d2=new DeliveryService(settings,reopened,(b,p)->{});var t2=new TaskService(settings,reopened,d2,DemoClient::new);
        try{new DailyScheduler(settings,reopened,t2,Clock.fixed(Instant.parse("2026-10-11T02:00:00Z"),ZoneOffset.UTC)).scan();assertEquals(2,reopened.all("SELECT * FROM runs").size());assertEquals(2,reopened.history(user.path("id").asText(),30).size());}finally{t2.close();d2.close();}
    }

    @Test void modelRequestResponseAndRealEndSignalAreLinkedWithoutStoringSensitiveContext()throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString(),"DEEPSEEK_API_KEY","fixture-secret-key"));var db=new Database(settings);var profile=Profile.validate(Json.object("nickname","audit","enabled",false));var user=db.createUser(profile);String rid=db.claim(user.path("id").asText(),profile,"demo","manual",null);
        var ctx=new ToolContext(rid,user.path("id").asText(),profile,db,settings);var demo=new DemoClient(profile);new AgentRunner(demo,new ToolRegistry(new NewsClient())).run(ctx);
        var requests=db.all("SELECT payload FROM events WHERE run_id=? AND kind='model_request' ORDER BY seq",rid);var replies=db.all("SELECT payload FROM events WHERE run_id=? AND kind='model' ORDER BY seq",rid);assertEquals(6,requests.size());assertEquals(6,replies.size());
        for(int i=0;i<6;i++){var request=Json.parse(requests.get(i).path("payload").asText());var response=Json.parse(replies.get(i).path("payload").asText());assertEquals(request.path("request_id"),response.path("request_id"));assertEquals(64,request.path("messages_sha256").asText().length());assertEquals(i<5?"tool_calls":"stop",response.path("finish_reason").asText());assertEquals("demo_script",response.path("finish_signal_source").asText());if(i<5)assertFalse(response.path("tool_requests").get(0).path("tool_call_id").asText().isBlank());}
        assertFalse(Json.stringify(db.events(ctx.userId,rid)).contains("fixture-secret-key"));
    }

    @Test void inboxDoesNotRequireSmtpButLiveEmailIsRejectedBeforeSavingPreferences() {
        var live=new Settings(Map.of("DATA_DIR",dir.toString(),"DEMO_MODE","false","DEEPSEEK_API_KEY","fixture-key"));var db=new Database(live);var service=new SubscriptionService(db,live);
        var inbox=service.create(Json.object("nickname","inbox","channel","inbox"));assertNotNull(inbox);
        var error=assertThrows(ApiException.class,()->service.update(inbox.path("id").asText(),Json.object("nickname","email","channel","email","email","test@example.com")));assertEquals(503,error.status);
        assertEquals("inbox",Json.parse(db.one("SELECT profile FROM subscriptions WHERE id=?",inbox.path("id").asText()).path("profile").asText()).path("channel").asText());
        var configured=new Settings(Map.of("DEMO_MODE","false","SMTP_HOST","smtp.example.com","SMTP_FROM","sender@example.com"));assertTrue(configured.smtpReady());
    }

    private void awaitRuns(Database db,int expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(db.one("SELECT COUNT(*) n FROM runs WHERE status='succeeded'").path("n").asInt()!=expected&&System.nanoTime()<end)Thread.sleep(20);assertEquals(expected,db.one("SELECT COUNT(*) n FROM runs WHERE status='succeeded'").path("n").asInt());}
}
