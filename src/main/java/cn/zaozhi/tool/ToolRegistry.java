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
        var readSchema=object("path",string(1,200),"offset",integer(0,2000000),"max_chars",integer(1,8000));readSchema.set("required",Json.tree(List.of("path")));
        add("read_file","分段读取只读资料、正文或草稿；最多 8000 字",readSchema,(a,c)->Json.tree(c.workspace.read(a.path("path").asText(),a.path("offset").asInt(0),a.path("max_chars").asInt(8000))));
        var searchSchema=object("keyword",string(1,100),"dir",string(1,200),"limit",integer(1,20));searchSchema.set("required",Json.tree(List.of("keyword","dir")));
        add("search_content","搜索本次正文、历史摘要、笔记与草稿；最多 20 条",searchSchema,(a,c)->c.workspace.search(a.path("keyword").asText(),a.path("dir").asText(),a.path("limit").asInt(20)));
        add("write_file","在本次工作区写入笔记或草稿，写文件不等于发布",object("path",string(1,200),"content",string(0,64000)),(a,c)->{c.workspace.write(a.path("path").asText(),a.path("content").asText()); return Json.object("saved",true);});
        add("bash","通过真实 PowerShell/Bash 执行固定 pwd 或 ls；禁止参数、组合命令和任意脚本",object("command",string(1,200)),(a,c)->new ShellExecutor(c.settings).execute(a.path("command").asText(),c.workspace,c.deadline,c::checkTime,event->c.db.event(c.runId,"shell",event)));
        add("fetch_rss","读取白名单 RSS；取得本次固定时间窗口来源",object("url",string(1,2000)),(a,c)->retrieved(c,c.settings.demo?demo(c):news.feed(a.path("url").asText(),c)));
        add("search_news","使用自己构造的关键词搜索 Google News RSS，可多次搜索或换查询",object("query",string(1,300)),(a,c)->{
            String query=a.path("query").asText()+" when:"+c.profile.path("lookback_hours").asInt()+"h";
            String url="https://news.google.com/rss/search?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
            return retrieved(c,c.settings.demo?demo(c):news.feed(url,c));
        });
        add("fetch_article","阅读本次检索取得的新闻正文；只能指定本次 source_id",object("source_id",string(1,100)),(a,c)->{
            JsonNode source=c.sources.get(a.path("source_id").asText());
            if(source==null) throw new IllegalArgumentException("未知来源标识");
            String sid=a.path("source_id").asText(),body=c.bodies.get(sid);boolean truncated=body!=null&&c.db.one("SELECT body_truncated FROM run_sources WHERE run_id=? AND source_id=?",c.runId,sid).path("body_truncated").asInt()==1;
            if(body==null){body=c.settings.demo?source.path("excerpt").asText():news.article(source.path("url").asText(),c);if(!c.settings.demo&&body.strip().length()<120)throw new java.io.IOException("未取得足够正文，不能把跳转占位页当作正文；可仅依据 RSS 标题/摘录说明限制，或选择其他来源");truncated=body.length()>=100000;body=NewsClient.clip(body,100000);String path="articles/"+sid+".txt";c.workspace.systemWrite(path,body);c.bodies.put(sid,body);c.db.execute("UPDATE run_sources SET body_ref=?,body_state='AVAILABLE',body_truncated=? WHERE run_id=? AND source_id=?",path,truncated?1:0,c.runId,sid);}
            return Json.object("source_id",sid,"excerpt",NewsClient.clip(body,800),"path","articles/"+sid+".txt","truncated",truncated);
        });
        add("get_history","获取当前订阅最近 30 份简报的来源索引，用于避免重复；无用户 ID 参数",object(),(a,c)->{
            ArrayNode history=Json.arr();
            for(JsonNode brief:c.db.history(c.userId,c.settings.mode(),30,0)) {
                ArrayNode items=Json.arr();
                for(JsonNode item:brief.path("items")) items.add(Json.object("title",item.path("title"),"url",item.path("url"),"source",item.path("source"),"published_at",item.path("published_at")));
                history.add(Json.object("title",brief.path("title"),"created_at",brief.path("created_at"),"items",items));
            }
            String index=c.settings.redact(Json.stringify(history));c.workspace.systemWrite("history/index.json",index);
            ArrayNode preview=Json.arr();for(JsonNode brief:history){if(preview.size()>=3)break;ObjectNode small=brief.deepCopy();ArrayNode few=Json.arr();for(JsonNode row:brief.path("items")){if(few.size()>=3)break;ObjectNode entry=row.deepCopy();entry.put("title",NewsClient.clip(entry.path("title").asText(),200));few.add(entry);}small.set("items",few);small.put("title",NewsClient.clip(small.path("title").asText(),200));preview.add(small);}
            return Json.object("brief_count",history.size(),"preview",preview,"path","history/index.json","truncated",history.size()>3||history.toString().length()>preview.toString().length());
        });
        JsonNode item=object("source_id",string(1,100),"summary",string(1,360),"why_it_matters",string(1,160));
        add("publish_brief","校验并暂存最终简报，之后自然结束才保存投递；无相关新闻允许空 items 并说明原因",
            object("title",string(1,200),"intro",string(1,300),"items",Json.object("type","array","items",item,"maxItems",10)),(a,c)->{
                ObjectNode accepted=new BriefValidator().validate(a,c);c.candidate=accepted; return Json.object("accepted",true,"items",accepted.path("items").size(),"message","候选已接受，尚未正式发布；正常结束后提交");
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
            return Json.object("ok",true,"error_code",null,"message","完成","retryable",false,"data",result,"truncated",result.path("truncated").asBoolean(false));
        } catch(Exception e) {
            if(e instanceof cn.zaozhi.agent.AgentFailure failure){if(!failure.code.equals("NO_USABLE_SOURCE"))throw failure;return Json.object("ok",false,"error_code",failure.code,"message",failure.getMessage(),"retryable",true,"data",null,"truncated",false);}
            ctx.checkTime();
            if(e instanceof ShellExecutor.ShellFailure failure)return Json.object("ok",false,"error_code",failure.code,"message",failure.getMessage(),"retryable",false,"data",failure.data,"truncated",failure.data.path("truncated").asBoolean());
            if(name.equals("fetch_rss")||name.equals("search_news"))ctx.failedSources++;
            return Json.object("ok",false,"error_code",e instanceof IllegalArgumentException?"POLICY_OR_ARGUMENT_REJECTED":"TOOL_FAILED","message",ctx.settings.redact(NewsClient.clip(e.getMessage(),400)),"retryable",!(e instanceof IllegalArgumentException),"data",null,"truncated",false);
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
    private static ObjectNode integer(int min,int max){return Json.object("type","integer","minimum",min,"maximum",max);}
    private static ArrayNode demo(ToolContext c){ArrayNode rows=NewsClient.demoNews();for(JsonNode row:rows)((ObjectNode)row).put("published_at",c.windowEnd.toString());return rows;}
    private static JsonNode retrieved(ToolContext c,ArrayNode rows)throws Exception{ArrayNode remembered=c.remember(rows),small=Json.arr();for(JsonNode row:remembered){if(small.size()>=5)break;small.add(Json.object("source_id",row.path("source_id"),"title",NewsClient.clip(row.path("title").asText(),160),"source",NewsClient.clip(row.path("source").asText(),80),"published_at",row.path("published_at"),"excerpt",NewsClient.clip(row.path("excerpt").asText(),180)));}return Json.object("sources",small,"total_results",remembered.size(),"truncated",!remembered.isEmpty(),"source_index","sources.json","preview_note","短预览不含长链接；完整来源在只读索引，按需分段读取。发布和正文读取使用 source_id。");}
    private static ObjectNode object(Object... pairs) {
        ObjectNode properties=Json.obj(); ArrayNode required=Json.arr();
        for(int i=0;i<pairs.length;i+=2) { properties.set((String)pairs[i],(JsonNode)pairs[i+1]); required.add((String)pairs[i]); }
        return Json.object("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
}
