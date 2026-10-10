package cn.zaozhi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private Json() {}
    public static JsonNode parse(String s) {
        try { return MAPPER.readTree(s); } catch (Exception e) { throw new IllegalArgumentException("JSON 格式无效"); }
    }
    public static String stringify(Object value) {
        try { return MAPPER.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("JSON 序列化失败", e); }
    }
    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }
    public static JsonNode tree(Object v) { return MAPPER.valueToTree(v); }
    public static ObjectNode object(Object... pairs) {
        ObjectNode o = obj();
        for (int i = 0; i < pairs.length; i += 2) o.set((String) pairs[i], tree(pairs[i+1]));
        return o;
    }
    public static String text(JsonNode n, String key, String fallback) { return n.path(key).asText(fallback); }
}
