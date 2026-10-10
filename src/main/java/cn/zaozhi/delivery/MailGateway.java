package cn.zaozhi.delivery;

import com.fasterxml.jackson.databind.JsonNode;

public interface MailGateway {
    void send(JsonNode brief,JsonNode profile) throws Exception;
}
