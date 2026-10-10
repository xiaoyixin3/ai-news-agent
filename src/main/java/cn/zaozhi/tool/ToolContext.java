package cn.zaozhi.tool;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ToolContext {
    public final String runId,userId;
    public final JsonNode profile;
    public final Database db;
    public final Settings settings;
    public final Workspace workspace;
    public final long deadline;
    public final Map<String,JsonNode> sources=new LinkedHashMap<>();
    public ObjectNode candidate;
    public ToolContext(String runId,String userId,JsonNode profile,Database db,Settings settings) throws Exception {
        this.runId=runId; this.userId=userId; this.profile=profile.deepCopy(); this.db=db; this.settings=settings;
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.runTimeout);
        workspace=new Workspace(settings.dataDir.resolve("workspaces").resolve(runId));
        workspace.write("preferences.json",Json.stringify(profile));
        workspace.write("sources.json",Json.stringify(NewsClient.SOURCES));
    }
    public void checkTime() {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline) throw new IllegalStateException("任务超过时间上限或已取消");
    }
    public ArrayNode remember(ArrayNode found) {
        for(JsonNode article:found) {
            if(sources.size()>=300 && !sources.containsKey(article.path("source_id").asText())) break;
            sources.put(article.path("source_id").asText(),article.deepCopy());
        }
        ArrayNode available=Json.arr();
        for(JsonNode article:found) if(sources.containsKey(article.path("source_id").asText())) available.add(article);
        return available;
    }
}
