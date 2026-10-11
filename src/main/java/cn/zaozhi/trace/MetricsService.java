package cn.zaozhi.trace;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public final class MetricsService {
    private final Database db;
    public MetricsService(Database db){this.db=db;}
    public ObjectNode summarize(String uid,String mode){
        var runs=db.one("SELECT COUNT(*) total,SUM(CASE WHEN status IN ('succeeded','failed') THEN 1 ELSE 0 END) finalized,SUM(CASE WHEN status='succeeded' THEN 1 ELSE 0 END) succeeded,COALESCE(SUM(known_input_tokens),0) input_tokens,COALESCE(SUM(known_output_tokens),0) output_tokens,COALESCE(SUM(reserved_tokens),0) accounted_tokens,COALESCE(SUM(unknown_usage_attempts),0) unknown_usage,COALESCE(SUM(network_bytes),0) network_bytes FROM runs WHERE subscription_id=? AND mode=?",uid,mode);
        var tools=db.one("SELECT COALESCE(SUM(CASE WHEN e.status IN ('ok','failed') THEN 1 ELSE 0 END),0) attempts,COALESCE(SUM(CASE WHEN e.status IN ('ok','failed','interrupted') THEN 1 ELSE 0 END),0) started_attempts,COALESCE(SUM(CASE WHEN e.status='ok' THEN 1 ELSE 0 END),0) successes,COALESCE(SUM(CASE WHEN e.status='not_executed' THEN 1 ELSE 0 END),0) not_executed,COALESCE(SUM(CASE WHEN e.status='interrupted' THEN 1 ELSE 0 END),0) interrupted,SUM(CASE WHEN e.error_code='POLICY_OR_ARGUMENT_REJECTED' THEN 1 ELSE 0 END) policy_rejections FROM events e JOIN runs r ON r.id=e.run_id WHERE r.subscription_id=? AND r.mode=? AND e.kind='tool'",uid,mode);
        var delivery=db.one("SELECT COUNT(*) total,SUM(CASE WHEN d.status='sent' OR (d.status='failed' AND d.next_attempt_at_ms IS NULL) THEN 1 ELSE 0 END) finalized,SUM(CASE WHEN d.status='sent' THEN 1 ELSE 0 END) sent,SUM(CASE WHEN d.status='unknown' THEN 1 ELSE 0 END) uncertain FROM deliveries d JOIN briefs b ON b.id=d.brief_id WHERE b.subscription_id=? AND b.mode=?",uid,mode);
        List<Long> latency=new ArrayList<>(),queue=new ArrayList<>();for(var run:db.all("SELECT started_at,finished_at,created_at FROM runs WHERE subscription_id=? AND mode=? AND status IN ('succeeded','failed') AND started_at IS NOT NULL ORDER BY created_at DESC LIMIT 10000",uid,mode)){
            try{long started=java.time.Instant.parse(run.path("started_at").asText()).toEpochMilli();if(!run.path("finished_at").isNull())latency.add(java.time.Instant.parse(run.path("finished_at").asText()).toEpochMilli()-started);queue.add(started-java.time.Instant.parse(run.path("created_at").asText()).toEpochMilli());}catch(Exception ignored){}
        }Collections.sort(latency);Collections.sort(queue);int coverage=0,count=0;for(var brief:db.history(uid,mode,1000,0)){Set<String> domains=new HashSet<>();for(var item:brief.path("items"))try{domains.add(java.net.URI.create(item.path("url").asText()).getHost());}catch(Exception ignored){}if(!domains.isEmpty()){coverage+=domains.size();count++;}}
        return Json.object("mode",mode,"runs",runs,"tools",tools,"deliveries",delivery,"success_rate",rate(runs.path("succeeded").asInt(),runs.path("finalized").asInt()),"tool_success_rate",rate(tools.path("successes").asInt(),tools.path("attempts").asInt()),"delivery_success_rate",rate(delivery.path("sent").asInt(),delivery.path("finalized").asInt()),"latency_p50_ms",percentile(latency,.5),"latency_p95_ms",percentile(latency,.95),"queue_p50_ms",percentile(queue,.5),"latency_sample",latency.size(),"average_source_domains",count==0?null:(double)coverage/count,"source_sample",count,"fact_support_rate",null,"semantic_duplicate_rate",null,"quality_note","事实支持率与事件级重复率待人工标注；当前仅有规范 URL 去重");
    }
    private static Double rate(int n,int d){return d==0?null:(double)n/d;}
    private static Long percentile(List<Long> xs,double p){return xs.isEmpty()?null:xs.get(Math.max(0,(int)Math.ceil(xs.size()*p)-1));}
}
