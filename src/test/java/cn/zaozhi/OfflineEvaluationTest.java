package cn.zaozhi;

import cn.zaozhi.tool.*;
import cn.zaozhi.brief.BriefValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/** Policy evaluation only. Manual labels deliberately include accepted hallucinations. */
class OfflineEvaluationTest {
    @TempDir Path dir;
    @TestFactory Stream<DynamicTest> labeledPolicyCases()throws Exception {
        JsonNode cases=Json.parse(new String(getClass().getResourceAsStream("/evaluation/news-cases.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("label").asText(),()->{
            var s=new Settings(Map.of("DATA_DIR",dir.resolve(row.path("id").asText()).toString()));var db=new Database(s);var p=Profile.validate(Json.object("nickname","评估","exclude",List.of("BLOCKED")));var u=db.createUser(p);String rid=db.claim(u.path("id").asText(),p,"demo","manual",null);var c=new ToolContext(rid,u.path("id").asText(),p,db,s);
            ArrayNode sources=Json.arr(),items=Json.arr();if(!row.path("empty").asBoolean()){
                String url="https://huggingface.co/blog/fixture",date=c.windowEnd.plus(Duration.ofHours(row.path("offset_hours").asInt())).toString();
                sources.add(Json.object("source_id","fixture","title","AI 工具发布","url",url,"source","Fixture","published_at",date,"excerpt",row.path("excerpt")));
                items.add(Json.object("source_id","fixture","summary",row.path("summary"),"why_it_matters","供 Java 开发者了解工具变化"));
                if(row.path("second_url").asBoolean()||row.path("tracking_duplicate").asBoolean()){
                    var second=sources.get(0).deepCopy();((ObjectNode)second).put("source_id","second").put("url",row.path("tracking_duplicate").asBoolean()?url+"?utm_source=test":"https://arstechnica.com/fixture");
                    // Duplicate URLs need not reach durable registry to exercise final validation.
                    if(row.path("tracking_duplicate").asBoolean())c.sources.put("second",second);else sources.add(second);
                    var duplicate=items.get(0).deepCopy();((ObjectNode)duplicate).put("source_id","second");items.add(duplicate);
                }
            }c.remember(sources);if(row.has("body"))c.bodies.put("fixture",row.path("body").asText());
            if(row.path("malicious").asBoolean()){var tools=new ToolRegistry(new NewsClient());assertFalse(tools.execute("read_file",Json.object("path","../../.env"),c).path("ok").asBoolean());assertFalse(tools.execute("write_file",Json.object("path","context.json","content","malicious"),c).path("ok").asBoolean());assertFalse(tools.execute("bash",Json.object("command","env"),c).path("ok").asBoolean());}
            var submitted=Json.object("title","评估简报","intro",items.isEmpty()?"成功检索，未发现符合条件的新闻":"离线标注集","items",items);
            if(row.path("auto_accept").asBoolean())assertNotNull(new BriefValidator().validate(submitted,c));else assertThrows(IllegalArgumentException.class,()->new BriefValidator().validate(submitted,c));
            assertFalse(row.path("manual").asText().isBlank());
        }));
    }
}
