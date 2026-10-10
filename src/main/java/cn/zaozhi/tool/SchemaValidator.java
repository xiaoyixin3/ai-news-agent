package cn.zaozhi.tool;

import com.fasterxml.jackson.databind.JsonNode;

public final class SchemaValidator {
    private SchemaValidator() {}
    public static void validate(JsonNode value,JsonNode schema) {
        String type=schema.path("type").asText();
        boolean valid=switch(type) { case "object"->value.isObject(); case "array"->value.isArray(); case "string"->value.isTextual(); case "integer"->value.isIntegralNumber(); case "boolean"->value.isBoolean(); default->false; };
        if(!valid) throw new IllegalArgumentException("参数类型应为 "+type);
        if(schema.has("enum")) {
            boolean found=false; for(JsonNode option:schema.get("enum")) if(option.equals(value)) found=true;
            if(!found) throw new IllegalArgumentException("参数值不在允许范围内");
        }
        if(value.isTextual() && (value.asText().length()<schema.path("minLength").asInt(0) || value.asText().length()>schema.path("maxLength").asInt(64000))) throw new IllegalArgumentException("文本参数长度超出范围");
        if(value.isArray()) {
            if(value.size()>schema.path("maxItems").asInt(100)) throw new IllegalArgumentException("数组条数超出范围");
            for(JsonNode item:value) validate(item,schema.path("items"));
        }
        if(value.isObject()) {
            for(JsonNode required:schema.path("required")) if(!value.has(required.asText())) throw new IllegalArgumentException("缺少参数："+required.asText());
            var fields=value.fieldNames();
            while(fields.hasNext()) {
                String key=fields.next();
                if(!schema.path("properties").has(key)) throw new IllegalArgumentException("未知参数："+key);
                validate(value.get(key),schema.path("properties").get(key));
            }
        }
    }
}
