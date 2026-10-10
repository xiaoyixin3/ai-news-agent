package cn.zaozhi.agent;

import cn.zaozhi.*;
import java.nio.charset.StandardCharsets;

/** Byte-based conservative input estimate; not an exact provider tokenizer. */
public final class RunBudget {
    private final Settings settings;private final Database db;private final String rid;
    private int calls,attempts;private long reserved,input,output,unknown,network;
    public RunBudget(Settings s,Database db,String rid){settings=s;this.db=db;this.rid=rid;}
    public void batch(int count){if(count>settings.maxCalls-calls)throw new AgentFailure("BUDGET_EXCEEDED","本轮工具请求超过剩余额度，整批未执行");calls+=count;persist();}
    public synchronized int reserve(String context){int estimate=context.getBytes(StandardCharsets.UTF_8).length+512;if(estimate>settings.integer("CONTEXT_TOKEN_LIMIT",24000))throw new AgentFailure("CONTEXT_BUDGET_EXCEEDED","上下文估计超限，请使用分段正文和更小结果");int allowance=estimate+4096;if(reserved+allowance>settings.integer("RUN_TOKEN_LIMIT",100000))throw new AgentFailure("BUDGET_EXCEEDED","累计模型预留预算不足");reserved+=allowance;attempts++;unknown++;persist();return allowance;}
    public synchronized void usage(int allocation,com.fasterxml.jackson.databind.JsonNode usage){if(usage.path("prompt_tokens").canConvertToInt()&&usage.path("completion_tokens").canConvertToInt()&&usage.path("prompt_tokens").asInt()>=0&&usage.path("completion_tokens").asInt()>=0){long in=usage.path("prompt_tokens").asLong(),out=usage.path("completion_tokens").asLong();input+=in;output+=out;reserved+=in+out-allocation;unknown--;persist();if(reserved>settings.integer("RUN_TOKEN_LIMIT",100000))throw new AgentFailure("BUDGET_EXCEEDED","实际模型用量超过预算");}}
    public synchronized void network(long bytes){network+=bytes;persist();if(network>settings.integer("NETWORK_BYTE_LIMIT",20*1024*1024))throw new AgentFailure("BUDGET_EXCEEDED","本次运行累计网络量超限");}
    private void persist(){db.execute("UPDATE runs SET tool_calls=?,model_attempts=?,known_input_tokens=?,known_output_tokens=?,reserved_tokens=?,unknown_usage_attempts=?,network_bytes=? WHERE id=?",calls,attempts,input,output,reserved,unknown,network,rid);}
}
