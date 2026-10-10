package cn.zaozhi.agent;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import cn.zaozhi.delivery.DeliveryService;
import cn.zaozhi.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Function;

@Service
public class TaskService {
    private final Settings settings;private final Database db;private final DeliveryService delivery;private final Function<JsonNode,LlmClient> clients;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->new Thread(r,"news-agent"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"agent-deadline"));
    private final ConcurrentMap<String,Thread> workers=new ConcurrentHashMap<>();
    @Autowired public TaskService(Settings s,Database db,DeliveryService d){this(s,db,d,p->s.demo?new DemoClient(p):new DeepSeekClient(s));}
    public TaskService(Settings s,Database db,DeliveryService d,Function<JsonNode,LlmClient> clients){settings=s;this.db=db;delivery=d;this.clients=clients;db.recover();}
    public String submit(String uid,JsonNode p,String trigger,String date){return submit(uid,trigger,date,null,null,null);}
    public synchronized String submit(String uid,String trigger,String date,String key,String hash,String retryOf){
        if(!settings.ready())throw new ApiException(503,"真实模式尚未配置 DEEPSEEK_API_KEY");
        var admitted=db.claim(uid,settings.mode(),trigger,date,key,hash,retryOf,settings);if(admitted.replay())return admitted.id();String rid=admitted.id();
        try{executor.execute(()->execute(rid,uid));}catch(RejectedExecutionException e){db.execute("DELETE FROM runs WHERE id=? AND status='queued'",rid);throw new ApiException(429,"生成队列暂不可用");}return rid;
    }
    private void execute(String rid,String uid){ScheduledFuture<?> timeout=null;workers.put(rid,Thread.currentThread());
        try{
            if(db.execute("UPDATE runs SET status='running',phase='PREPARING',started_at=? WHERE id=? AND subscription_id=? AND status='queued'",Database.now(),rid,uid)!=1)return;
            var run=db.one("SELECT preferences,deadline_at_ms FROM runs WHERE id=? AND subscription_id=?",rid,uid);JsonNode profile=Json.parse(run.path("preferences").asText());long remaining=run.path("deadline_at_ms").asLong()-System.currentTimeMillis();
            if(remaining<=0)throw new AgentFailure("DEADLINE_EXCEEDED","任务在排队期间超过期限");Thread worker=Thread.currentThread();
            timeout=watchdog.schedule(()->{if(db.execute("UPDATE runs SET status='failed',phase=NULL,finished_at=?,error_code='DEADLINE_EXCEEDED',error='任务超过总时间上限' WHERE id=? AND status='running'",Database.now(),rid)==1)worker.interrupt();},remaining,TimeUnit.MILLISECONDS);
            ToolContext ctx=new ToolContext(rid,uid,profile,db,settings);var result=new AgentRunner(clients.apply(profile),new ToolRegistry(new NewsClient())).run(ctx);ctx.checkTime();
            db.execute("UPDATE runs SET phase='COMMITTING' WHERE id=? AND status='running'",rid);db.finish(rid,result);delivery.wake();
            db.event(rid,"terminal",Json.object("status","succeeded","message","简报已提交"));
        }catch(Exception e){String code=e instanceof AgentFailure a?a.code:"RUN_FAILED";String error=settings.redact(NewsClient.clip(e.getMessage(),512));db.execute("UPDATE runs SET status='failed',phase=NULL,finished_at=?,error_code=?,error=? WHERE id=? AND status IN ('queued','running')",Database.now(),code,error,rid);
            try{db.event(rid,"error",Json.object("message",error,"error_code",code));}catch(RuntimeException ignored){}
        }finally{if(timeout!=null)timeout.cancel(false);workers.remove(rid);Thread.interrupted();}
    }
    public void cancel(String uid,String rid){if(db.one("SELECT id FROM runs WHERE id=? AND subscription_id=?",rid,uid)==null)throw new ApiException(404,"任务不存在");if(db.execute("UPDATE runs SET status='cancelled',phase=NULL,finished_at=?,error_code='USER_CANCELLED',error='用户取消' WHERE id=? AND subscription_id=? AND status IN ('queued','running')",Database.now(),rid,uid)!=1)throw new ApiException(409,"任务已进入终态");Thread t=workers.get(rid);if(t!=null)t.interrupt();}
    public int availableCapacity(){return Math.max(0,34-db.one("SELECT COUNT(*) n FROM runs WHERE status IN ('queued','running')").path("n").asInt());}
    @PreDestroy public void close(){executor.shutdownNow();watchdog.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
