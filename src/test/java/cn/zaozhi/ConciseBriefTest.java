package cn.zaozhi;

import cn.zaozhi.tool.*;
import cn.zaozhi.brief.BriefValidator;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConciseBriefTest {
    @TempDir Path dir;
    private ToolContext context()throws Exception {
        var settings=new Settings(Map.of("DATA_DIR",dir.toString()));var db=new Database(dir);var profile=Profile.validate(Json.object("nickname","concise"));var user=db.createUser(profile);
        var ctx=new ToolContext(db.claim(user.path("id").asText(),profile,"demo","manual",null),user.path("id").asText(),profile,db,settings);
        var sources=NewsClient.demoNews();for(var source:sources)((ObjectNode)source).put("published_at",ctx.windowEnd.toString());ctx.remember(sources);return ctx;
    }
    private static ObjectNode brief(String summary){return Json.object("title","简报","intro","说明","items",Json.arr().add(Json.object("source_id","demo-agent","summary",summary,"why_it_matters","建议关注工具追踪")));}
    @Test void rssAndBodyLimitsRejectInsteadOfSilentlyTruncatingOrReplacingCandidate()throws Exception {
        var ctx=context();var registry=new ToolRegistry(new NewsClient());assertTrue(registry.execute("publish_brief",brief("有来源的短摘要"),ctx).path("ok").asBoolean());var accepted=ctx.candidate.deepCopy();
        assertFalse(registry.execute("publish_brief",brief("字".repeat(161)),ctx).path("ok").asBoolean());assertEquals(accepted,ctx.candidate);
        ctx.bodies.put("demo-agent","正文证据");assertTrue(registry.execute("publish_brief",brief("字".repeat(360)),ctx).path("ok").asBoolean());var bodyAccepted=ctx.candidate.deepCopy();assertFalse(registry.execute("publish_brief",brief("字".repeat(361)),ctx).path("ok").asBoolean());assertEquals(bodyAccepted,ctx.candidate);
        assertThrows(IllegalArgumentException.class,()->new BriefValidator().validate(brief("字".repeat(361)),ctx));
    }
    @Test void obviousOtherSourceTitleCannotBeBlendedIntoRssOnlyItem()throws Exception {
        var ctx=context();String foreignTitle=ctx.sources.get("demo-model").path("title").asText();var invalid=brief("本条还包括："+foreignTitle);assertThrows(IllegalArgumentException.class,()->new BriefValidator().validate(invalid,ctx));
        ctx.bodies.put("demo-agent","本篇正文确实引用了 "+foreignTitle);assertEquals(1,new BriefValidator().validate(invalid,ctx).path("items").size());
    }
    @Test void summaryLimitsDoNotPretendToVerifyUnsupportedFacts()throws Exception {
        var ctx=context();assertEquals(1,new BriefValidator().validate(brief("未经证据支持：性能提升 500%。"),ctx).path("items").size());
        var invalid=brief("短摘要");((ObjectNode)invalid.path("items").get(0)).put("why_it_matters","字".repeat(161));assertThrows(IllegalArgumentException.class,()->new BriefValidator().validate(invalid,ctx));
        var longIntro=brief("短摘要").put("intro","字".repeat(301));assertThrows(IllegalArgumentException.class,()->new BriefValidator().validate(longIntro,ctx));
    }
    @Test void rejectedPublicationReportsExactFieldAndUnicodeLength()throws Exception {
        var ctx=context();ctx.bodies.put("demo-agent","body");var result=new ToolRegistry(new NewsClient()).execute("publish_brief",brief("字".repeat(361)),ctx);String feedback=result.path("message").asText();assertTrue(feedback.contains("$.items[0].summary"));assertTrue(feedback.contains("361"));assertTrue(feedback.contains("360"));
        assertDoesNotThrow(()->SchemaValidator.validate(Json.tree("😀"),Json.object("type","string","minLength",1,"maxLength",1)));
    }
}
