package cn.zaozhi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

public final class Profile {
    private static final Set<String> FIELDS = Set.of("nickname","role","topics","keywords","exclude","time","timezone","channel","email","enabled","max_items","lookback_hours");
    public static ObjectNode validate(JsonNode input) {
        if (!input.isObject()) throw new IllegalArgumentException("订阅应为 JSON 对象");
        input.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k)) throw new IllegalArgumentException("未知订阅字段：" + k); });
        ObjectNode p = Json.object("nickname", string(input,"nickname","",80), "role",string(input,"role","AI 开发者",200),
            "time",string(input,"time","09:00",5), "timezone",string(input,"timezone","Asia/Shanghai",100),
            "channel",string(input,"channel","inbox",20), "email",string(input,"email","",200));
        if (p.path("nickname").asText().isBlank()) throw new IllegalArgumentException("请填写昵称");
        if (!p.path("time").asText().matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("时间应为 HH:MM");
        try { ZoneId.of(p.path("timezone").asText()); } catch (Exception e) { throw new IllegalArgumentException("无效时区"); }
        String channel = p.path("channel").asText();
        if (!List.of("inbox","email").contains(channel)) throw new IllegalArgumentException("推送渠道无效");
        if (channel.equals("email") && !p.path("email").asText().matches("[^\\s@<>\\r\\n]+@[^\\s@<>\\r\\n]+\\.[^\\s@<>\\r\\n]+")) throw new IllegalArgumentException("请填写有效邮箱");
        for (String field : List.of("topics","keywords","exclude")) {
            JsonNode a = input.get(field);
            if (a == null) a = field.equals("topics") ? Json.tree(List.of("大模型","AI Agent")) : Json.arr();
            if (!a.isArray() || a.size() > 20) throw new IllegalArgumentException("关键词应为不超过 20 项的数组");
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            for (JsonNode term : a) {
                if (!term.isTextual() || term.asText().length() > 100) throw new IllegalArgumentException("关键词最长 100 字");
                if (!term.asText().isBlank()) terms.add(term.asText().strip());
            }
            p.set(field, Json.tree(terms));
        }
        if (input.has("enabled") && !input.get("enabled").isBoolean()) throw new IllegalArgumentException("enabled 应为布尔值");
        p.put("enabled", input.path("enabled").asBoolean(true));
        p.put("max_items", number(input,"max_items",5,1,10));
        p.put("lookback_hours", number(input,"lookback_hours",48,1,168));
        return p;
    }
    private static String string(JsonNode n, String key, String fallback, int max) {
        if (n.has(key) && !n.get(key).isTextual()) throw new IllegalArgumentException(key + " 应为文本");
        String value = n.path(key).asText(fallback).strip();
        if (value.length() > max) throw new IllegalArgumentException(key + " 超过长度上限");
        return value;
    }
    private static int number(JsonNode n,String k,int fallback,int min,int max) {
        if (n.has(k) && !n.get(k).isIntegralNumber()) throw new IllegalArgumentException(k + " 应为整数");
        int value = n.path(k).asInt(fallback);
        if (value < min || value > max) throw new IllegalArgumentException(k + " 超出范围");
        return value;
    }
    public static String dueDate(JsonNode profile, Instant now) {
        ZoneId zone=ZoneId.of(profile.path("timezone").asText());ZonedDateTime local=now.atZone(zone);
        ZonedDateTime target=local.toLocalDate().atTime(LocalTime.parse(profile.path("time").asText())).atZone(zone).withEarlierOffsetAtOverlap();
        return profile.path("enabled").asBoolean()&&!now.isBefore(target.toInstant())?local.toLocalDate().toString():null;
    }
}
