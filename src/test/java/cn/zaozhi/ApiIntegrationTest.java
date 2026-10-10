package cn.zaozhi;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    @LocalServerPort int port;
    static {
        // Settings loads process env; Spring's test properties do not replace it.
        System.setProperty("ai.agent.test.data",System.getProperty("java.io.tmpdir")+"/news-agent-api-"+Database.id());
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class Configuration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Settings testSettings() { return new Settings(java.util.Map.of("DATA_DIR",System.getProperty("ai.agent.test.data"),"SCHEDULER_ENABLED","false")); }
    }
    private final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String token,JsonNode body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null) b.header("Authorization","Bearer "+token);
        b.header("Content-Type","application/json");
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void completeApiFlowAndCrossUserIsolation() throws Exception {
        assertEquals(200,request("GET","/api/health",null,null).statusCode());
        assertEquals(401,request("GET","/api/briefs",null,null).statusCode());
        var first=request("POST","/api/subscriptions",null,Json.object("nickname","接口用户","enabled",false)); assertEquals(201,first.statusCode());
        String token=Json.parse(first.body()).path("token").asText();
        var second=request("POST","/api/subscriptions",null,Json.object("nickname","其他用户","enabled",false)); String other=Json.parse(second.body()).path("token").asText();
        var started=request("POST","/api/runs",token,null); assertEquals(202,started.statusCode());
        String rid=Json.parse(started.body()).path("id").asText();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        JsonNode run;
        do { run=Json.parse(request("GET","/api/runs/"+rid,token,null).body()); if(run.path("status").asText().equals("succeeded")) break; Thread.sleep(50); } while(System.nanoTime()<end);
        assertEquals("succeeded",run.path("status").asText(),run.toString());
        assertEquals(404,request("GET","/api/runs/"+rid,other,null).statusCode());
        assertEquals(404,request("GET","/api/runs/"+rid+"/events",other,null).statusCode());
        var list=Json.parse(request("GET","/api/briefs",token,null).body()); assertEquals(1,list.path("total").asInt());
        String bid=list.path("items").get(0).path("id").asText();
        assertEquals("demo_inbox",list.path("items").get(0).path("delivery_status").asText());
        assertEquals(404,request("GET","/api/briefs/"+bid,other,null).statusCode());
        assertEquals(404,request("POST","/api/briefs/"+bid+"/retry-delivery",other,null).statusCode());
        assertEquals(400,request("GET","/api/briefs?page=0",token,null).statusCode());
        assertEquals(5,Json.parse(request("GET","/api/runs/"+rid+"/events",token,null).body()).findValuesAsText("name").size());
        assertEquals(200,request("PUT","/api/me",token,Json.object("nickname","已修改","enabled",false)).statusCode());
        assertEquals("已修改",Json.parse(request("GET","/api/me",token,null).body()).path("profile").path("nickname").asText());
    }
}
