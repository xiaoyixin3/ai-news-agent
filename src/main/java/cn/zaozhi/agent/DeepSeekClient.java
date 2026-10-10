package cn.zaozhi.agent;

import cn.zaozhi.*;
import cn.zaozhi.news.BoundedBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DeepSeekClient implements LlmClient {
    private final Settings settings;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public DeepSeekClient(Settings settings) { this.settings=settings; }
    @Override public ObjectNode next(ArrayNode messages, ArrayNode tools, long deadline) throws Exception {
        return request(messages,tools,deadline,null);
    }
    @Override public ObjectNode next(ArrayNode messages,ArrayNode tools,long deadline,RunBudget budget)throws Exception{return request(messages,tools,deadline,budget);}
    private ObjectNode request(ArrayNode messages,ArrayNode tools,long deadline,RunBudget budget)throws Exception {
        if(settings.apiKey.isBlank()) throw new IllegalStateException("真实模式需要配置 DEEPSEEK_API_KEY");
        String body=Json.stringify(Json.object("model",settings.model,"messages",messages,"tools",tools,"tool_choice","auto","stream",false,
            "thinking",Json.object("type","disabled"),"max_tokens",4096));
        for(int attempt=0;attempt<3;attempt++) {
            int allocation=budget==null?0:budget.reserve(body);
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            if(remaining<=0) throw new IllegalStateException("任务超过总时间上限");
            HttpRequest request=HttpRequest.newBuilder(URI.create(settings.baseUrl+"/chat/completions"))
                .timeout(Duration.ofMillis(Math.min(settings.llmTimeout*1000L,remaining))).header("Content-Type","application/json")
                .header("Authorization","Bearer "+settings.apiKey).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<byte[]> response;
            try {
                var future=http.sendAsync(request,info->new BoundedBody(2_000_000));
                try { response=future.get(Math.min(settings.llmTimeout*1000L,remaining),TimeUnit.MILLISECONDS); }
                catch(Exception e) { future.cancel(true); throw e; }
            } catch(Exception e) {
                if(e instanceof InterruptedException) { Thread.currentThread().interrupt(); throw e; }
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException("任务已取消");
                if(attempt==2) throw new IllegalStateException("模型请求超时或网络不可达，请检查模型地址和网络");
                pause(attempt,deadline); continue;
            }
            if(response.statusCode()!=200) {
                if(attempt<2 && Set.of(429,500,502,503,504).contains(response.statusCode())) { pause(attempt,deadline); continue; }
                throw new IllegalStateException("模型服务返回 HTTP "+response.statusCode()+"，请检查密钥、模型名称或额度");
            }
            JsonNode result=Json.parse(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
            if(budget!=null)budget.usage(allocation,result.path("usage"));
            JsonNode choice=result.path("choices").path(0);
            String finish=choice.path("finish_reason").asText();
            if(!Set.of("stop","tool_calls").contains(finish)) throw new IllegalStateException("模型响应未完整结束："+finish);
            JsonNode message=choice.path("message");
            if(!message.isObject() || !message.path("role").asText().equals("assistant")) throw new IllegalStateException("模型返回的消息协议无效");
            JsonNode calls=message.path("tool_calls");boolean hasCalls=calls.isArray()&&!calls.isEmpty();
            if((finish.equals("tool_calls")&&!hasCalls)||(finish.equals("stop")&&hasCalls)||(message.has("tool_calls")&&!calls.isArray()))throw new AgentFailure("INVALID_LLM_PROTOCOL","结束信号与工具请求不一致");
            ObjectNode clean=Json.object("role","assistant","content",message.path("content").isTextual()?message.path("content"):Json.tree(null));
            if(message.has("tool_calls")) clean.set("tool_calls",message.get("tool_calls"));
            clean.put("_finish_reason",finish);clean.set("_usage",result.path("usage"));
            return clean;
        }
        throw new IllegalStateException("模型调用失败");
    }
    private static void pause(int attempt,long deadline) throws InterruptedException {
        long wait=Math.min(500L*(1L<<attempt),TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
        if(wait<=0) throw new IllegalStateException("任务超过总时间上限");
        Thread.sleep(wait);
    }
}
