package cn.zaozhi;

import cn.zaozhi.agent.*;
import cn.zaozhi.tool.*;
import cn.zaozhi.trace.MetricsService;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InterruptedBatchTest {
    @TempDir Path dir;
    private ToolContext context(Map<String,String> overrides)throws Exception {
        var config=new HashMap<>(overrides);config.put("DATA_DIR",dir.toString());config.put("DEMO_MODE","false");
        var settings=new Settings(config);var db=new Database(dir);var profile=Profile.validate(Json.object("nickname","batch audit","enabled",false));var user=db.createUser(profile);
        String rid=db.claim(user.path("id").asText(),"live","manual",null,null,null,null,settings).id();db.execute("UPDATE runs SET status='running' WHERE id=?",rid);
        return new ToolContext(rid,user.path("id").asText(),profile,db,settings);
    }
    private static ObjectNode batch() {
        var answer=DemoClient.call("write_file",Json.object("path","notes/before.txt","content","before"));
        var calls=(ArrayNode)answer.get("tool_calls");calls.add(DemoClient.call("fetch_rss",Json.object("url","https://huggingface.co/blog/feed.xml")).get("tool_calls").get(0));
        calls.add(DemoClient.call("write_file",Json.object("path","notes/after.txt","content","after")).get("tool_calls").get(0));return answer;
    }
    private void interrupted(String code,String status)throws Exception {
        var ctx=context(Map.of());AtomicInteger decisions=new AtomicInteger();var request=batch();
        var news=new NewsClient(){@Override public ArrayNode feed(String url,ToolContext c)throws Exception {
            c.db.execute("UPDATE runs SET status=?,error_code=?,error=? WHERE id=?",status,code,"fixture stopped",c.runId);Thread.currentThread().interrupt();throw new java.io.IOException("interrupted network");
        }};
        try {
            var error=assertThrows(AgentFailure.class,()->new AgentRunner((m,t,d)->{decisions.incrementAndGet();return request;},new ToolRegistry(news)).run(ctx));assertEquals(code,error.code);assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
        assertEquals(1,decisions.get());assertTrue(Files.exists(ctx.workspace.resolve("notes/before.txt")));assertFalse(Files.exists(ctx.workspace.resolve("notes/after.txt")));
        var ends=ctx.db.all("SELECT step_id,status,error_code FROM events WHERE run_id=? AND kind='tool' ORDER BY seq",ctx.runId);
        assertEquals(3,ends.size());assertEquals("ok",ends.get(0).path("status").asText());assertEquals("interrupted",ends.get(1).path("status").asText());assertEquals("not_executed",ends.get(2).path("status").asText());
        assertEquals(request.path("tool_calls").get(1).path("id").asText(),ends.get(1).path("step_id").asText());assertEquals(code,ends.get(1).path("error_code").asText());assertEquals(code,ends.get(2).path("error_code").asText());
        assertEquals(2,ctx.db.all("SELECT id FROM events WHERE run_id=? AND kind='tool_start'",ctx.runId).size());assertTrue(ctx.db.history(ctx.userId,30).isEmpty());
        var metrics=new MetricsService(ctx.db).summarize(ctx.userId,"live");assertEquals(1,metrics.path("tools").path("attempts").asInt());assertEquals(2,metrics.path("tools").path("started_attempts").asInt());assertEquals(1,metrics.path("tools").path("not_executed").asInt());assertEquals(1,metrics.path("tools").path("interrupted").asInt());assertEquals(1.0,metrics.path("tool_success_rate").asDouble());
    }
    @Test void cancelAuditsStartedAndRemainingCallsWithoutRollingBackEarlierWrite()throws Exception{interrupted("USER_CANCELLED","cancelled");}
    @Test void watchdogTimeoutKeepsDeadlineReasonInsteadOfCancellation()throws Exception{interrupted("DEADLINE_EXCEEDED","failed");}
    @Test void entireOverBudgetBatchIsAuditedWithoutAnyToolExecution()throws Exception {
        var ctx=context(Map.of("MAX_TOOL_CALLS","2"));var error=assertThrows(AgentFailure.class,()->new AgentRunner((m,t,d)->batch(),new ToolRegistry(new NewsClient())).run(ctx));assertEquals("BUDGET_EXCEEDED",error.code);
        assertEquals(3,ctx.db.all("SELECT id FROM events WHERE run_id=? AND status='not_executed'",ctx.runId).size());assertTrue(ctx.db.all("SELECT id FROM events WHERE run_id=? AND kind='tool_start'",ctx.runId).isEmpty());assertFalse(Files.exists(ctx.workspace.resolve("notes/before.txt")));
        assertEquals(0,new MetricsService(ctx.db).summarize(ctx.userId,"live").path("tools").path("attempts").asInt());
    }
    @Test void cancellationAfterModelResponseStillAuditsEveryRequestedCall()throws Exception {
        var ctx=context(Map.of());var error=assertThrows(AgentFailure.class,()->new AgentRunner((m,t,d)->{ctx.db.execute("UPDATE runs SET status='cancelled',error_code='USER_CANCELLED',error='cancel' WHERE id=?",ctx.runId);return batch();},new ToolRegistry(new NewsClient())).run(ctx));assertEquals("USER_CANCELLED",error.code);
        assertEquals(3,ctx.db.all("SELECT id FROM events WHERE run_id=? AND status='not_executed'",ctx.runId).size());assertFalse(Files.exists(ctx.workspace.resolve("notes/before.txt")));
    }
    @Test void duplicateProtocolIdsRejectWholeBatchBeforeSideEffects()throws Exception {
        var ctx=context(Map.of());var answer=batch();((ObjectNode)answer.path("tool_calls").get(2)).put("id",answer.path("tool_calls").get(0).path("id").asText());
        var error=assertThrows(AgentFailure.class,()->new AgentRunner((m,t,d)->answer,new ToolRegistry(new NewsClient())).run(ctx));assertEquals("INVALID_TOOL_PROTOCOL",error.code);assertFalse(Files.exists(ctx.workspace.resolve("notes/before.txt")));assertEquals(3,ctx.db.all("SELECT DISTINCT step_id FROM events WHERE run_id=? AND status='not_executed'",ctx.runId).size());
    }
}

