package cn.zaozhi.tool;

import cn.zaozhi.*;
import cn.zaozhi.agent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ToolContext {
    public final String runId,userId;
    public final JsonNode profile;
    public final Database db;
    public final Settings settings;
    public final Workspace workspace;
    public final long deadline;
    public final Instant windowStart,windowEnd;
    public final RunBudget budget;
    public final Map<String,JsonNode> sources=new LinkedHashMap<>();
    public final Map<String,String> bodies=new HashMap<>();
    public int usableSources,failedSources;
    public ObjectNode candidate;
    public ToolContext(String runId,String uid,JsonNode profile,Database db,Settings settings)throws Exception {
        this.runId=runId;userId=uid;this.db=db;this.settings=settings;ObjectNode safe=(ObjectNode)Json.parse(settings.redact(Json.stringify(profile)));safe.remove("email");this.profile=safe;
        var run=db.one("SELECT * FROM runs WHERE id=? AND subscription_id=?",runId,uid);if(run==null)throw new IllegalArgumentException("任务不属于当前订阅");
        windowStart=Instant.ofEpochMilli(run.path("window_start_ms").asLong());windowEnd=Instant.ofEpochMilli(run.path("window_end_ms").asLong());deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,run.path("deadline_at_ms").asLong()-System.currentTimeMillis()));
        budget=new RunBudget(settings,db,runId);workspace=new Workspace(settings.dataDir.resolve("workspaces").resolve(runId),settings.integer("WORKSPACE_BYTE_LIMIT",10*1024*1024));
        ArrayNode history=db.history(uid,settings.mode(),30,0);for(JsonNode row:history)((ObjectNode)row).remove(List.of("delivery_recipient","delivery_id","delivery_error"));
        String context=Json.stringify(Json.object("preferences",safe,"window_start",windowStart.toString(),"window_end",windowEnd.toString()));workspace.systemWrite("context.json",context);workspace.systemWrite("preferences.json",Json.stringify(safe));workspace.systemWrite("sources.json","[]");workspace.systemWrite("history.json",settings.redact(Json.stringify(history)));workspace.systemWrite("feeds.json",Json.stringify(NewsClient.SOURCES));
    }
    public void checkTime(){if(Thread.currentThread().isInterrupted())throw new AgentFailure("CANCELLED","任务已取消");if(System.nanoTime()>=deadline)throw new AgentFailure("DEADLINE_EXCEEDED","任务超过时间上限");var row=db.one("SELECT status FROM runs WHERE id=? AND subscription_id=?",runId,userId);if(row==null||Set.of("failed","cancelled","succeeded").contains(row.path("status").asText()))throw new AgentFailure("CANCELLED","任务已进入终态");}
    public ArrayNode remember(ArrayNode found)throws Exception {usableSources++;for(JsonNode article:found){String sid=article.path("source_id").asText();if(sources.size()>=100&&!sources.containsKey(sid))break;sources.put(sid,article.deepCopy());db.execute("INSERT INTO run_sources(run_id,source_id,title,url,canonical_url,publisher,published_at,fetched_at,excerpt) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id,source_id) DO UPDATE SET excerpt=excluded.excerpt",runId,sid,article.path("title").asText(),article.path("url").asText(),NewsClient.canonical(article.path("url").asText()),article.path("source").asText(),article.path("published_at").asText(),Database.now(),article.path("excerpt").asText());}
        ArrayNode snapshot=Json.arr();sources.values().forEach(snapshot::add);workspace.systemWrite("sources.json",Json.stringify(snapshot));ArrayNode available=Json.arr();for(JsonNode article:found)if(sources.containsKey(article.path("source_id").asText()))available.add(article);return available;
    }
}
