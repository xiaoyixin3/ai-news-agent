package cn.zaozhi.tool;

import cn.zaozhi.*;
import cn.zaozhi.brief.BriefValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ToolRegistry {
    @FunctionalInterface private interface Action { JsonNode execute(JsonNode args,ToolContext ctx) throws Exception; }
    private final Map<String,AgentTool> tools=new LinkedHashMap<>();
    public ToolRegistry(NewsClient news) {
        add("list_dir","列出本次运行工作区的文件；根目录使用 .",object("path",string(1,200)),(a,c)->c.workspace.list(a.path("path").asText()));
        add("read_file","读取本次工作区文件，例如 preferences.json 或 sources.json",object("path",string(1,200)),(a,c)->Json.tree(c.workspace.read(a.path("path").asText())));
        add("search_content","按关键词搜索本次工作区文本",object("keyword",string(1,100),"dir",string(1,200)),(a,c)->c.workspace.search(a.path("keyword").asText(),a.path("dir").asText()));
        add("write_file","在本次工作区写入笔记或草稿，写文件不等于发布",object("path",string(1,200),"content",string(0,64000)),(a,c)->{c.workspace.write(a.path("path").asText(),a.path("content").asText()); return Json.object("saved",true);});
        add("bash","执行受限 Shell，仅可使用 pwd 或 ls，不能添加参数",object("command",Json.object("type","string","enum",List.of("pwd","ls"))),(a,c)->Json.tree(c.workspace.bash(a.path("command").asText(),c.deadline)));
        add("fetch_rss","读取公开白名单 RSS；返回有效时间窗口内的来源索引",object("url",string(1,2000)),(a,c)->c.remember(c.settings.demo?NewsClient.demoNews():news.feed(a.path("url").asText(),c.profile.path("lookback_hours").asInt(),c.deadline)));
        add("search_news","使用自己构造的关键词搜索 Google News RSS，可多次搜索或换查询",object("query",string(1,300)),(a,c)->{
            String query=a.path("query").asText()+" when:"+c.profile.path("lookback_hours").asInt()+"h";
            String url="https://news.google.com/rss/search?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
            return c.remember(c.settings.demo?NewsClient.demoNews():news.feed(url,c.profile.path("lookback_hours").asInt(),c.deadline));
        });
        add("fetch_article","阅读本次检索取得的新闻正文；只能指定本次 source_id",object("source_id",string(1,100)),(a,c)->{
            JsonNode source=c.sources.get(a.path("source_id").asText());
            if(source==null) throw new IllegalArgumentException("未知来源标识");
            return Json.object("source_id",a.path("source_id"),"content",c.settings.demo?source.path("excerpt").asText():news.article(source.path("url").asText(),c.deadline));
        });
        add("get_history","获取当前订阅最近 30 份简报的来源索引，用于避免重复；无用户 ID 参数",object(),(a,c)->{
            ArrayNode history=Json.arr();
            for(JsonNode brief:c.db.history(c.userId,30)) {
                if(!brief.path("mode").asText().equals(c.settings.mode())) continue;
                ArrayNode items=Json.arr();
                for(JsonNode item:brief.path("items")) items.add(Json.object("title",item.path("title"),"url",item.path("url"),"source",item.path("source"),"published_at",item.path("published_at")));
                history.add(Json.object("title",brief.path("title"),"created_at",brief.path("created_at"),"items",items));
            }
            return history;
        });
        JsonNode item=object("source_id",string(1,100),"summary",string(1,1500),"why_it_matters",string(1,600));
        add("publish_brief","校验并暂存最终简报，之后自然结束才保存投递；无相关新闻允许空 items 并说明原因",
            object("title",string(1,200),"intro",string(1,1500),"items",Json.object("type","array","items",item,"maxItems",10)),(a,c)->{
                c.candidate=null;
                c.candidate=new BriefValidator().validate(a,c); return Json.object("accepted",true,"items",c.candidate.path("items").size(),"message","候选简报已通过校验，可以自然结束");
            });
    }
    public ArrayNode definitions() { ArrayNode a=Json.arr(); tools.values().forEach(t->a.add(t.schema())); return a; }
    public JsonNode execute(String name,JsonNode args,ToolContext ctx) {
        ctx.checkTime();
        try {
            AgentTool tool=tools.get(name);
            if(tool==null) throw new IllegalArgumentException("未知工具："+name);
            SchemaValidator.validate(args,tool.schema().path("function").path("parameters"));
            JsonNode result=tool.execute(args,ctx); ctx.checkTime();
            if(Json.stringify(result).length()>160_000) throw new IllegalArgumentException("工具结果超出大小上限，请缩小查询");
            return Json.object("ok",true,"data",result);
        } catch(Exception e) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("任务被取消");
            return Json.object("ok",false,"error_code",e instanceof IllegalArgumentException?"INVALID_ARGUMENT":"TOOL_FAILED","message",ctx.settings.redact(NewsClient.clip(e.getMessage(),400)));
        }
    }
    private void add(String name,String description,ObjectNode parameters,Action action) {
        ObjectNode schema=Json.object("type","function","function",Json.object("name",name,"description",description,"parameters",parameters));
        tools.put(name,new AgentTool() {
            public String name() { return name; }
            public JsonNode schema() { return schema; }
            public JsonNode execute(JsonNode args,ToolContext ctx) throws Exception { return action.execute(args,ctx); }
        });
    }
    private static ObjectNode string(int min,int max) { return Json.object("type","string","minLength",min,"maxLength",max); }
    private static ObjectNode object(Object... pairs) {
        ObjectNode properties=Json.obj(); ArrayNode required=Json.arr();
        for(int i=0;i<pairs.length;i+=2) { properties.set((String)pairs[i],(JsonNode)pairs[i+1]); required.add((String)pairs[i]); }
        return Json.object("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
}
