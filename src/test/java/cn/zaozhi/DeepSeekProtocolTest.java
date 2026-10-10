package cn.zaozhi;

import cn.zaozhi.agent.*;
import cn.zaozhi.tool.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekProtocolTest {
    @Test void sendsFunctionSchemasAndPreservesToolIdsWithoutExposingReasoning() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<com.fasterxml.jackson.databind.JsonNode> requests=new ArrayList<>();
        server.createContext("/v1/chat/completions",exchange->{
            try {
                assertEquals("Bearer fixture-key",exchange.getRequestHeaders().getFirst("Authorization"));
                var body=Json.parse(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); requests.add(body);
                var msg=DemoClient.call("get_history",Json.obj()); msg.put("reasoning_content","not for UI");
                byte[] response=Json.stringify(Json.object("choices",List.of(Json.object("finish_reason","tool_calls","message",msg)))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var config=new Settings(Map.of("DEMO_MODE","false","DEEPSEEK_API_KEY","fixture-key","LLM_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1"));
            var client=new DeepSeekClient(config);
            var first=client.next(Json.arr().add(Json.object("role","user","content","news")),new ToolRegistry(new NewsClient()).definitions(),System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
            assertEquals(10,requests.get(0).path("tools").size());
            assertFalse(first.has("reasoning_content"));
            var messages=Json.arr().add(first).add(Json.object("role","tool","tool_call_id",first.path("tool_calls").get(0).path("id"),"content","[]"));
            client.next(messages,new ToolRegistry(new NewsClient()).definitions(),System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
            assertEquals(first.path("tool_calls").get(0).path("id"),requests.get(1).path("messages").get(1).path("tool_call_id"));
        } finally { server.stop(0); }
    }
    @Test void rejectsTruncatedModelResponse() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{byte[] body="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}".getBytes(); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();});
        server.start();
        try {
            var s=new Settings(Map.of("DEMO_MODE","false","DEEPSEEK_API_KEY","fixture-key","LLM_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()));
            assertThrows(IllegalStateException.class,()->new DeepSeekClient(s).next(Json.arr(),Json.arr(),System.nanoTime()+TimeUnit.SECONDS.toNanos(5)));
        } finally { server.stop(0); }
    }
}
