package cn.zaozhi.evaluation;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** Offline, source-backed review. Assisted labels never populate the human metric. */
public final class QualityEvaluation {
    public ObjectNode template(JsonNode bundle){
        ArrayNode reviews=Json.arr();for(JsonNode run:bundle.path("runs"))for(JsonNode item:run.path("brief").path("items"))reviews.add(Json.object("run_id",run.path("run_id"),"source_id",item.path("source_id"),"event_id","","facts",Json.arr(),"notes","待对照当前来源逐项标注"));
        return Json.object("reviewer",Json.object("kind","unreviewed","name",""),"reviews",reviews);
    }
    public ObjectNode evaluate(JsonNode bundle,JsonNode labels){
        String kind=labels.path("reviewer").path("kind").asText();require(Set.of("human","assisted","unreviewed").contains(kind),"reviewer.kind 必须为 human、assisted 或 unreviewed");
        if(!kind.equals("unreviewed"))require(!labels.path("reviewer").path("name").asText().isBlank(),"需要标注者与标注方式");
        Map<String,JsonNode> originals=new LinkedHashMap<>(),evidence=new HashMap<>();Set<String> roles=new TreeSet<>();int runs=0;
        require(bundle.path("runs").isArray(),"runs 必须为数组");
        for(JsonNode run:bundle.path("runs")){
            runs++;roles.add(run.path("role").asText());require(!run.path("run_id").asText().isBlank(),"run_id 不能为空");
            for(JsonNode item:run.path("brief").path("items")){
                String key=key(run.path("run_id").asText(),item.path("source_id").asText());require(originals.putIfAbsent(key,item)==null,"重复的运行/来源");
                JsonNode source=run.path("evidence").path(item.path("source_id").asText());String text=source.path("text").asText();require(source.isObject()&&!text.isBlank(),"缺少对应来源证据");require(Database.hash(text).equals(source.path("sha256").asText()),"证据哈希不符");require(source.path("scope").asText().equals(item.path("evidence_scope").asText()),"证据范围不符");evidence.put(key,source);
            }
        }
        Set<String> seen=new HashSet<>();Map<String,Integer> groups=new HashMap<>();int supported=0,unsupported=0,uncertain=0,reviewed=0,classified=0;ArrayNode checks=Json.arr();
        require(labels.path("reviews").isArray(),"reviews 必须为数组");
        for(JsonNode review:labels.path("reviews")){
            String rid=review.path("run_id").asText(),sid=review.path("source_id").asText(),key=key(rid,sid);require(originals.containsKey(key)&&seen.add(key),"标注的运行/来源不存在或重复");require(review.path("facts").isArray(),"facts 必须为数组");
            if(!review.path("facts").isEmpty())reviewed++;
            Set<String> claims=new HashSet<>();for(JsonNode fact:review.path("facts")){
                String verdict=fact.path("verdict").asText(),quote=fact.path("quote").asText(),claim=fact.path("claim").asText();require(!claim.isBlank()&&claims.add(claim),"事实不能为空或重复");require(Set.of("supported","unsupported","uncertain").contains(verdict),"无效事实标签");require(!kind.equals("unreviewed"),"未审核模板不能包含已判断事实");
                if(verdict.equals("supported"))require(!quote.isBlank(),"支持标签必须给出当前来源原文");if(!quote.isBlank())require(normalized(evidence.get(key).path("text").asText()).contains(normalized(quote)),"引文不在该条目的来源内，不能使用其他来源作证");
                switch(verdict){case "supported"->supported++;case "unsupported"->unsupported++;default->uncertain++;}
                checks.add(Json.object("run_id",rid,"source_id",sid,"claim",claim,"verdict",verdict,"quote",quote,"evidence_sha256",evidence.get(key).path("sha256")));
            }
            String event=review.path("event_id").asText();if(!event.isBlank()){classified++;groups.merge(key(rid,event),1,Integer::sum);}
        }
        require(seen.size()==originals.size(),"每条新闻均须有标注条目，未审核事实可以留空");int facts=supported+unsupported+uncertain,duplicates=groups.values().stream().mapToInt(n->n-1).sum();Double rate=facts==0?null:(double)supported/facts;
        return Json.object("reviewer",labels.path("reviewer"),"runs",runs,"roles",roles,"items",originals.size(),"reviewed_items",reviewed,"checked_facts",facts,"supported_facts",supported,"unsupported_facts",unsupported,"uncertain_facts",uncertain,
            "fact_support_rate",kind.equals("human")?rate:null,"assisted_fact_support_rate",kind.equals("assisted")?rate:null,"classified_items",classified,"duplicate_items",duplicates,"semantic_duplicate_rate",classified==originals.size()&&classified>0?(double)duplicates/classified:null,"duplicate_judgment",kind,"checks",checks,
            "note","事实分母为全部核验事实，含不可判断项；事件重复只在同一份简报内计算。辅助标注不等于人工验收，不更新站内人工质量指标。");
    }
    private static String key(String a,String b){return a+"\u0000"+b;}
    private static String normalized(String text){return text.replaceAll("\\s+"," ").strip();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    public static void main(String[] args)throws Exception{
        require(args.length==3,"用法：QualityEvaluation bundle.json labels.json report.json，或 --template bundle.json labels.json");var evaluation=new QualityEvaluation();
        if(args[0].equals("--template")){Files.writeString(Path.of(args[2]),Json.stringify(evaluation.template(Json.parse(Files.readString(Path.of(args[1]))))),StandardOpenOption.CREATE_NEW);System.out.println("已创建待标注模板");return;}
        String input=Files.readString(Path.of(args[0])),labels=Files.readString(Path.of(args[1]));ObjectNode report=evaluation.evaluate(Json.parse(input),Json.parse(labels));report.put("bundle_sha256",Database.hash(input));report.put("labels_sha256",Database.hash(labels));Files.writeString(Path.of(args[2]),Json.stringify(report));System.out.println("评估完成，人工与辅助标注结果已分开记录");
    }
}
