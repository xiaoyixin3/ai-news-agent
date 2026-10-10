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
        JsonNode items=submitted.path("items");
        if(!items.isArray() || items.size()>ctx.profile.path("max_items").asInt()) throw new IllegalArgumentException("简报条数超过订阅上限");
        Set<String> used=new HashSet<>();
        for(JsonNode brief:ctx.db.history(ctx.userId,30)) {
            if(!brief.path("mode").asText().equals(ctx.settings.mode())) continue;
            for(JsonNode item:brief.path("items")) used.add(NewsClient.canonical(item.path("url").asText()));
        }
        ArrayNode clean=Json.arr();
        for(JsonNode item:items) {
            JsonNode source=ctx.sources.get(item.path("source_id").asText());
            if(source==null) throw new IllegalArgumentException("source_id 不属于本次已取得的新闻来源");
            Instant published;
            try { published=Instant.parse(source.path("published_at").asText()); } catch(Exception e) { throw new IllegalArgumentException("新闻缺少有效发布时间"); }
            long age=Duration.between(published,Instant.now()).getSeconds();
            if(age < -300 || age>ctx.profile.path("lookback_hours").asInt()*3600L) throw new IllegalArgumentException("新闻不在订阅时间窗口内");
            String url=NewsClient.canonical(source.path("url").asText());
            if(!used.add(url)) throw new IllegalArgumentException("新闻链接已在本次简报或近期历史中出现");
            if(item.path("summary").asText().isBlank() || item.path("why_it_matters").asText().isBlank()) throw new IllegalArgumentException("每条新闻必须包含摘要和与你相关的意义");
            String haystack=(source.path("title").asText()+" "+source.path("excerpt").asText()+" "+item.path("summary").asText()+" "+item.path("why_it_matters").asText()).toLowerCase(Locale.ROOT);
            for(JsonNode term:ctx.profile.path("exclude")) if(haystack.contains(term.asText().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("新闻包含排除关键词："+term.asText());
            ObjectNode resolved=source.deepCopy(); resolved.remove("excerpt"); resolved.put("url",url);
            resolved.put("summary",item.path("summary").asText()); resolved.put("why_it_matters",item.path("why_it_matters").asText());
            clean.add(resolved);
        }
        return Json.object("title",submitted.path("title"),"intro",submitted.path("intro"),"items",clean);
    }
}
