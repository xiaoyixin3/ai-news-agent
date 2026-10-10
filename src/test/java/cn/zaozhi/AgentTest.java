package cn.zaozhi;

import cn.zaozhi.agent.*;
import cn.zaozhi.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    @TempDir Path dir;
    private ToolContext context(Map<String,String> overrides) throws Exception {
        Map<String,String> config=new HashMap<>(overrides); config.put("DATA_DIR",dir.toString()); config.put("DEMO_MODE","true");
        Settings s=new Settings(config); Database db=new Database(dir);
        var p=Profile.validate(Json.object("nickname","Agent tester")); var u=db.createUser(p);
        String rid=db.claim(u.path("id").asText(),p,"demo","manual",null);
        return new ToolContext(rid,u.path("id").asText(),p,db,s);
    }
    @Test void honorsDifferentToolOrdersAndContinuesAfterToolError() throws Exception {
        for(var order:List.of(List.of("get_history","read_file","search_news"),List.of("search_news","get_history","read_file"))) {
            ToolContext c=context(Map.of());
            int[] step={0};
            LlmClient scripted=(messages,tools,deadline)-> {
                int index=step[0]++;
                if(index==0) return DemoClient.call("read_file",Json.object("path","missing.txt"));
                if(index==1) assertFalse(Json.parse(messages.get(messages.size()-1).path("content").asText()).path("ok").asBoolean());
                if(index<=3) {
                    String name=order.get(index-1);
                    return DemoClient.call(name,switch(name) { case "read_file"->Json.object("path","preferences.json"); case "search_news"->Json.object("query","AI"); default->Json.obj(); });
                }
                if(index==4) return DemoClient.call("publish_brief",Json.object("title","测试简报","intro","测试演示","items",Json.arr().add(Json.object("source_id","demo-agent","summary","合成样例摘要","why_it_matters","用于验证调用协议"))));
                return Json.object("role","assistant","content","完成");
            };
            var result=new AgentRunner(scripted,new ToolRegistry(new NewsClient())).run(c);
            assertEquals(1,result.path("items").size());
            var events=c.db.all("SELECT payload FROM events WHERE run_id=? AND kind='tool' ORDER BY id",c.runId);
            for(int i=0;i<order.size();i++) assertEquals(order.get(i),Json.parse(events.get(i+1).path("payload").asText()).path("name").asText());
        }
    }
    @Test void endingWithoutPublishingAndLimitsCannotProduceSuccess() throws Exception {
        var c=context(Map.of());
        assertThrows(IllegalStateException.class,()->new AgentRunner((m,t,d)->Json.object("role","assistant","content","done"),new ToolRegistry(new NewsClient())).run(c));
        var limited=context(Map.of("MAX_ROUNDS","2"));
        assertThrows(IllegalStateException.class,()->new AgentRunner((m,t,d)->DemoClient.call("get_history",Json.obj()),new ToolRegistry(new NewsClient())).run(limited));
        assertNull(limited.candidate);
        assertTrue(limited.db.history(limited.userId,30).isEmpty());
    }
    @Test void allToolsExistAndFileShellBoundariesAreEnforced() throws Exception {
        var c=context(Map.of()); var registry=new ToolRegistry(new NewsClient());
        assertEquals(10,registry.definitions().size());
        assertFalse(registry.execute("read_file",Json.object("path","../../.env"),c).path("ok").asBoolean());
        assertFalse(registry.execute("bash",Json.object("command","pwd; env"),c).path("ok").asBoolean());
        assertTrue(registry.execute("bash",Json.object("command","pwd"),c).path("ok").asBoolean());
        assertTrue(registry.execute("bash",Json.object("command","ls"),c).path("ok").asBoolean());
        assertTrue(registry.execute("list_dir",Json.object("path","."),c).path("data").isArray());
        assertTrue(registry.execute("write_file",Json.object("path","note.txt","content","AI tools"),c).path("ok").asBoolean());
        assertEquals(1,registry.execute("search_content",Json.object("keyword","tools","dir","."),c).path("data").size());
        assertTrue(registry.execute("fetch_rss",Json.object("url","https://huggingface.co/blog/feed.xml"),c).path("ok").asBoolean());
        assertTrue(registry.execute("fetch_article",Json.object("source_id","demo-agent"),c).path("ok").asBoolean());
    }
}
