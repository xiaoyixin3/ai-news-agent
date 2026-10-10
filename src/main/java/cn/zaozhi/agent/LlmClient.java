package cn.zaozhi.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface LlmClient {
    ObjectNode next(ArrayNode messages, ArrayNode tools, long deadlineNanos) throws Exception;
}
