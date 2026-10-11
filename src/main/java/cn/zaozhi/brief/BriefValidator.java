package cn.zaozhi.brief;

import cn.zaozhi.*;
import cn.zaozhi.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

public final class BriefValidator {
    public ObjectNode validate(JsonNode submitted,ToolContext ctx) {
        if(submitted.path("title").asText().isBlank() || submitted.path("intro").asText().isBlank()) throw new IllegalArgumentException("标题与导语不能为空；无新闻时请说明原因");
        bounded(submitted.path("title").asText(),200,"标题");bounded(submitted.path("intro").asText(),300,"导语");
        JsonNode items=submitted.path("items");
        if(!items.isArray() || items.size()>ctx.profile.path("max_items").asInt()) throw new IllegalArgumentException("简报条数超过订阅上限");
        Set<String> used=new HashSet<>();
        for(JsonNode brief:ctx.db.history(ctx.userId,ctx.settings.mode(),30,0)) {
            for(JsonNode item:brief.path("items")) used.add(NewsClient.canonical(item.path("url").asText()));
        }
        ArrayNode clean=Json.arr();
        for(JsonNode item:items) {
            JsonNode source=ctx.sources.get(item.path("source_id").asText());
            if(source==null) throw new IllegalArgumentException("source_id 不属于本次已取得的新闻来源");
            Instant published;
            try { published=Instant.parse(source.path("published_at").asText()); } catch(Exception e) { throw new IllegalArgumentException("新闻缺少有效发布时间"); }
            if(published.isAfter(ctx.windowEnd)||published.isBefore(ctx.windowStart))throw new IllegalArgumentException("新闻不在任务创建时冻结的时间窗口内");
            String url=NewsClient.canonical(source.path("url").asText());
            if(!used.add(url)) throw new IllegalArgumentException("新闻链接已在本次简报或近期历史中出现");
            if(item.path("summary").asText().isBlank() || item.path("why_it_matters").asText().isBlank()) throw new IllegalArgumentException("每条新闻必须包含摘要和与你相关的意义");
            String sid=item.path("source_id").asText(),summary=item.path("summary").asText();
            bounded(summary,ctx.bodies.containsKey(sid)?360:160,"摘要（仅 RSS 证据最多 160 字，正文证据最多 360 字）");bounded(item.path("why_it_matters").asText(),160,"意义说明");
            String text=normalized(summary),evidence=normalized(source.path("title").asText()+" "+source.path("excerpt").asText()+" "+ctx.bodies.getOrDefault(sid,""));
            for(var other:ctx.sources.entrySet())if(!other.getKey().equals(sid)){
                String otherTitle=normalized(other.getValue().path("title").asText()),otherUrl=other.getValue().path("url").asText();
                if((otherTitle.length()>=16&&text.contains(otherTitle)&&!evidence.contains(otherTitle))||(!otherUrl.isBlank()&&summary.contains(otherUrl)&&!evidence.contains(normalized(otherUrl))))throw new IllegalArgumentException("摘要混入另一来源的标题或链接；请分别提交来源，或只保留当前来源有证据的内容");
            }
            String haystack=(submitted.path("title").asText()+" "+submitted.path("intro").asText()+" "+source.path("title").asText()+" "+source.path("excerpt").asText()+" "+ctx.bodies.getOrDefault(item.path("source_id").asText(),"")+" "+item.path("summary").asText()+" "+item.path("why_it_matters").asText()).toLowerCase(Locale.ROOT);
            for(JsonNode term:ctx.profile.path("exclude")) if(haystack.contains(term.asText().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("新闻包含排除关键词："+term.asText());
            ObjectNode resolved=source.deepCopy(); resolved.remove("excerpt"); resolved.put("url",url);
            resolved.put("summary",item.path("summary").asText()); resolved.put("why_it_matters",item.path("why_it_matters").asText());
            resolved.put("evidence_scope",ctx.bodies.containsKey(item.path("source_id").asText())?"extracted_body":"rss_excerpt");var registered=ctx.db.one("SELECT body_truncated FROM run_sources WHERE run_id=? AND source_id=?",ctx.runId,item.path("source_id").asText());resolved.put("body_truncated",registered!=null&&registered.path("body_truncated").asBoolean());
            clean.add(resolved);
        }
        for(JsonNode term:ctx.profile.path("exclude"))if((submitted.path("title").asText()+" "+submitted.path("intro").asText()).toLowerCase(Locale.ROOT).contains(term.asText().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("标题或导语包含排除词");
        if(clean.isEmpty()&&ctx.usableSources==0)throw new cn.zaozhi.agent.AgentFailure("NO_USABLE_SOURCE","未取得可用来源，不能用空简报代替来源失败");
        ObjectNode result=Json.object("title",submitted.path("title"),"intro",submitted.path("intro"),"items",clean,"empty_brief",clean.isEmpty());if(Json.stringify(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("简报总字节超过 64 KiB");return result;
    }
    private static void bounded(String text,int max,String field){if(text.codePointCount(0,text.length())>max)throw new IllegalArgumentException(field+"超过 "+max+" 字；请简写后重新提交，系统不会截断已接受候选");}
    private static String normalized(String text){return text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").strip();}
}
