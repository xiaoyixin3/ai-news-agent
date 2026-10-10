package cn.zaozhi;

import cn.zaozhi.brief.BriefValidator;
import cn.zaozhi.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsQualityTest {
    @TempDir Path dir;
    private ToolContext context() throws Exception {
        var s=new Settings(Map.of("DATA_DIR",dir.toString())); var db=new Database(dir);
        var p=Profile.validate(Json.object("nickname","质量测试","exclude",List.of("广告"))); var u=db.createUser(p);
        String rid=db.claim(u.path("id").asText(),p,"demo","manual",null);
        return new ToolContext(rid,u.path("id").asText(),p,db,s);
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode brief(String id) {
        return Json.object("title","测试简报","intro","说明","items",Json.arr().add(Json.object("source_id",id,"summary","有效新闻摘要","why_it_matters","与开发者相关")));
    }
    @Test void refusesUnknownExpiredUndatedExcludedAndDuplicateSources() throws Exception {
        var c=context(); var validator=new BriefValidator();
        assertThrows(IllegalArgumentException.class,()->validator.validate(brief("unknown"),c));
        var source=NewsClient.demoNews().get(0).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("published_at",c.windowEnd.toString()); c.remember(Json.arr().add(source)); source=c.sources.get("demo-agent");
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("published_at","2020-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class,()->validator.validate(brief("demo-agent"),c));
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).remove("published_at");
        assertThrows(IllegalArgumentException.class,()->validator.validate(brief("demo-agent"),c));
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("published_at",c.windowEnd.toString()).put("excerpt","包含广告");
        assertThrows(IllegalArgumentException.class,()->validator.validate(brief("demo-agent"),c));
        ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("excerpt","合成样例");
        var duplicate=brief("demo-agent"); ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.get("items")).add(duplicate.get("items").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->validator.validate(duplicate,c));
        c.db.execute("UPDATE runs SET status='running' WHERE id=?",c.runId);
        c.db.finish(c.runId,validator.validate(brief("demo-agent"),c));
        assertThrows(IllegalArgumentException.class,()->validator.validate(brief("demo-agent"),c));
        assertEquals(0,validator.validate(Json.object("title","空简报","intro","没有新条目","items",List.of()),c).path("items").size());
    }
    @Test void rssFiltersUnknownDatesAndRetainsOriginalPublicationTime() throws Exception {
        Instant now=Instant.parse("2026-10-10T10:00:00Z");
        String rss="""
            <rss version="2.0"><channel><title>测试源</title><link>https://huggingface.co</link><description>Fixture</description>
            <item><title>有效新闻</title><link>https://huggingface.co/blog/ok?utm_source=test</link><pubDate>Sat, 10 Oct 2026 09:00:00 GMT</pubDate><description>&lt;p&gt;公开摘要&lt;/p&gt;</description></item>
            <item><title>无日期</title><link>https://huggingface.co/blog/nodate</link></item>
            <item><title>过期</title><link>https://huggingface.co/blog/old</link><pubDate>Thu, 01 Oct 2026 09:00:00 GMT</pubDate></item>
            <item><title>危险链接</title><link>javascript:alert(1)</link><pubDate>Sat, 10 Oct 2026 09:00:00 GMT</pubDate></item>
            </channel></rss>
            """;
        var news=NewsClient.parseFeed(rss.getBytes(StandardCharsets.UTF_8),"https://huggingface.co/blog/feed.xml",48,now);
        assertEquals(1,news.size()); assertEquals("2026-10-10T09:00:00Z",news.get(0).path("published_at").asText());
        assertEquals("https://huggingface.co/blog/ok",news.get(0).path("url").asText());
        assertEquals("公开摘要",news.get(0).path("excerpt").asText());
    }
    @Test void urlAndWorkspaceAccessCannotEscapeBoundaries() throws Exception {
        assertEquals("https://huggingface.co/b?ref=useful",NewsClient.canonical("https://huggingface.co/a/../b?utm_source=x&ref=useful#fragment"));
        assertThrows(IllegalArgumentException.class,()->NewsClient.validateTarget("http://127.0.0.1/admin"));
        assertThrows(IllegalArgumentException.class,()->NewsClient.validateTarget("https://huggingface.co.attacker.example/feed"));
        assertThrows(IllegalArgumentException.class,()->NewsClient.validateTarget("https://user:pass@huggingface.co/feed"));
        assertFalse(NewsClient.publicAddress(java.net.InetAddress.getByName("10.0.0.1")));
        assertFalse(NewsClient.publicAddress(java.net.InetAddress.getByName("::1")));
        Workspace ws=new Workspace(dir.resolve("workspace"));
        assertThrows(IllegalArgumentException.class,()->ws.read("../secrets"));
        Path outside=dir.resolve("outside"); Files.createDirectories(outside); Files.writeString(outside.resolve("secret"),"private");
        try { Files.createSymbolicLink(dir.resolve("workspace/link"),outside); }
        catch(Exception e) {
            if(!System.getProperty("os.name").toLowerCase().contains("win")) throw e;
            Process junction=new ProcessBuilder("cmd.exe","/c","mklink","/J",dir.resolve("workspace/link").toString(),outside.toString()).redirectErrorStream(true).start();
            junction.getInputStream().readAllBytes(); assertEquals(0,junction.waitFor(),"Windows junction creation");
        }
        assertThrows(IllegalArgumentException.class,()->ws.read("link/secret"));
    }
}
