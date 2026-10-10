package cn.zaozhi.agent;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Offline scripted responses exercise the same runner. This is NOT autonomous LLM inference. */
public final class DemoClient implements LlmClient {
    private final JsonNode profile;
    private int step;
    public DemoClient(JsonNode profile) { this.profile=profile; }
    public ObjectNode next(ArrayNode messages,ArrayNode tools,long deadline) {
        return switch(step++) {
            case 0 -> call("read_file",Json.object("path","preferences.json"));
            case 1 -> call("get_history",Json.obj());
            case 2 -> call("search_news",Json.object("query",profile.path("topics").toString()));
            case 3 -> call("write_file",Json.object("path","notes.md","content","离线演示：使用合成样例展示工具调用、筛选和发布，不代表 DeepSeek 实际调用。"));
            case 4 -> call("publish_brief",brief(messages));
            default -> Json.object("role","assistant","content","演示简报已提交。此流程是模拟响应，不证明模型自主决策。");
        };
    }
    private ObjectNode brief(ArrayNode messages) {
        Set<String> used=new HashSet<>();
        for(JsonNode message:messages) if(message.path("role").asText().equals("tool")) {
            JsonNode response=Json.parse(message.path("content").asText());
            JsonNode data=response.path("data");
            if(data.isArray()) for(JsonNode row:data) for(JsonNode item:row.path("items")) used.add(item.path("url").asText());
        }
        ArrayNode items=Json.arr();
        for(JsonNode source:NewsClient.demoNews()) {
            String combined=(source.path("title").asText()+source.path("excerpt").asText()).toLowerCase(Locale.ROOT);
            boolean excluded=false;
            for(JsonNode term:profile.path("exclude")) if(combined.contains(term.asText().toLowerCase(Locale.ROOT))) excluded=true;
            if(excluded || used.contains(source.path("url").asText()) || items.size()>=profile.path("max_items").asInt(5)) continue;
            items.add(Json.object("source_id",source.path("source_id"),"summary",source.path("excerpt"),"why_it_matters","演示：为“"+profile.path("role").asText()+"”提供关注“"+profile.path("topics").toString()+"”的阅读示例。"));
        }
        return Json.object("title","离线演示 · AI 新闻简报","intro",items.isEmpty()?"合成样例已在历史中出现或被排除，本次没有新的演示条目。":"以下内容是合成样例，非实时新闻。真实模式将由 DeepSeek 自主检索并整理。","items",items);
    }
    public static ObjectNode call(String name,JsonNode args) {
        return Json.object("role","assistant","content",null,"tool_calls",Json.arr().add(Json.object("id","call_"+Database.id(),"type","function","function",Json.object("name",name,"arguments",Json.stringify(args)))));
    }
}
