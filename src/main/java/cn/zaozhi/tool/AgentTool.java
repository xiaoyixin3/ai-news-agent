package cn.zaozhi.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {
    String name();
    JsonNode schema();
    JsonNode execute(JsonNode arguments,ToolContext context) throws Exception;
}
