package cn.zaozhi.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface LlmClient {
    ObjectNode next(ArrayNode messages, ArrayNode tools, long deadlineNanos) throws Exception;
    default ObjectNode next(ArrayNode messages,ArrayNode tools,long deadline,RunBudget budget)throws Exception {
        int allocation=budget.reserve(cn.zaozhi.Json.stringify(messages)+cn.zaozhi.Json.stringify(tools));ObjectNode response=next(messages,tools,deadline);budget.usage(allocation,response.path("_usage"));return response;
    }
}
