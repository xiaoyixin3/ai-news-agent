package cn.zaozhi.tool;

import com.fasterxml.jackson.databind.JsonNode;

public final class SchemaValidator {
    private SchemaValidator() {}
    public static void validate(JsonNode value,JsonNode schema) {
        validate(value,schema,"$");
    }
    private static void validate(JsonNode value,JsonNode schema,String path) {
        String type=schema.path("type").asText();
        boolean valid=switch(type) { case "object"->value.isObject(); case "array"->value.isArray(); case "string"->value.isTextual(); case "integer"->value.isIntegralNumber(); case "boolean"->value.isBoolean(); default->false; };
        if(!valid) throw new IllegalArgumentException("参数类型应为 "+type);
        if(value.isIntegralNumber()&&(!value.canConvertToInt()||value.asLong()<schema.path("minimum").asLong(Integer.MIN_VALUE)||value.asLong()>schema.path("maximum").asLong(Integer.MAX_VALUE)))throw new IllegalArgumentException("整数超出允许范围");
        if(schema.has("enum")) {
            boolean found=false; for(JsonNode option:schema.get("enum")) if(option.equals(value)) found=true;
            if(!found) throw new IllegalArgumentException("参数值不在允许范围内");
        }
        if(value.isTextual()){int length=value.asText().codePointCount(0,value.asText().length()),min=schema.path("minLength").asInt(0),max=schema.path("maxLength").asInt(64000);if(length<min||length>max)throw new IllegalArgumentException(path+" 文本长度为 "+length+" 字，允许 "+min+"-"+max+" 字；请显著压缩此字段后重新提交");}
        if(value.isArray()) {
            if(value.size()>schema.path("maxItems").asInt(100)) throw new IllegalArgumentException("数组条数超出范围");
            for(int i=0;i<value.size();i++)validate(value.get(i),schema.path("items"),path+"["+i+"]");
        }
        if(value.isObject()) {
            for(JsonNode required:schema.path("required")) if(!value.has(required.asText())) throw new IllegalArgumentException("缺少参数："+required.asText());
            var fields=value.fieldNames();
            while(fields.hasNext()) {
                String key=fields.next();
                if(!schema.path("properties").has(key)) throw new IllegalArgumentException("未知参数："+key);
                validate(value.get(key),schema.path("properties").get(key),path+"."+key);
            }
        }
    }
}
