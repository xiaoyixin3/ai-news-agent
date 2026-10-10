package cn.zaozhi.agent;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import cn.zaozhi.delivery.DeliveryService;
import cn.zaozhi.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Function;

@Service
public class TaskService {
    private final Settings settings;
    private final Database db;
    private final DeliveryService delivery;
    private final Function<JsonNode,LlmClient> clients;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->new Thread(r,"news-agent"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"agent-deadline"));
    private final Semaphore capacity=new Semaphore(34);
    @Autowired public TaskService(Settings settings,Database db,DeliveryService delivery) {
        this(settings,db,delivery,p->settings.demo?new DemoClient(p):new DeepSeekClient(settings));
    }
    public TaskService(Settings settings,Database db,DeliveryService delivery,Function<JsonNode,LlmClient> clients) {
        this.settings=settings; this.db=db; this.delivery=delivery; this.clients=clients; db.recover();
    }
    public synchronized String submit(String uid,JsonNode profile,String trigger,String date) {
        if(!settings.ready()) throw new ApiException(503,"真实模式尚未配置 DEEPSEEK_API_KEY，请在本地环境变量中设置");
        if(db.one("SELECT id FROM runs WHERE user_id=? AND status IN ('queued','running')",uid)!=null) throw new ApiException(409,"已有生成任务正在执行，请等待完成");
        if(!capacity.tryAcquire()) throw new ApiException(429,"生成队列已满，请稍后重试");
        String rid;
        try {
            rid=db.claim(uid,profile,settings.mode(),trigger,date);
            if(rid==null) { capacity.release(); throw new ApiException(409,"该订阅已领取当天任务或已有活动任务"); }
        } catch(RuntimeException e) { if(!(e instanceof ApiException)) capacity.release(); throw e; }
        JsonNode snapshot=profile.deepCopy();
        try { executor.execute(()->execute(rid,uid,snapshot)); }
        catch(RejectedExecutionException e) {
            capacity.release(); db.execute("UPDATE runs SET status='failed',finished_at=?,error='任务队列暂不可用' WHERE id=?",Database.now(),rid);
            throw new ApiException(429,"任务队列暂不可用");
        }
        return rid;
    }
    private void execute(String rid,String uid,JsonNode profile) {
        ScheduledFuture<?> timeout=null;
        try {
            if(db.execute("UPDATE runs SET status='running' WHERE id=? AND status='queued'",rid)!=1) return;
            Thread worker=Thread.currentThread();
            timeout=watchdog.schedule(()-> {
                if(db.execute("UPDATE runs SET status='failed',finished_at=?,error='任务超过总时间上限' WHERE id=? AND status='running'",Database.now(),rid)==1) worker.interrupt();
            },settings.runTimeout,TimeUnit.SECONDS);
            ToolContext ctx=new ToolContext(rid,uid,profile,db,settings);
            ObjectNode result=new AgentRunner(clients.apply(profile),new ToolRegistry(new NewsClient())).run(ctx);
            ctx.checkTime();
            String bid=db.finish(rid,result);
            timeout.cancel(false);
            delivery.deliver(bid,profile);
        } catch(Exception e) {
            String error=settings.redact(NewsClient.clip(e.getMessage(),500));
            db.execute("UPDATE runs SET status='failed',finished_at=?,error=? WHERE id=? AND status IN ('queued','running')",Database.now(),error,rid);
            db.event(rid,"error",Json.object("message",error));
        } finally { if(timeout!=null) timeout.cancel(false); Thread.interrupted(); capacity.release(); }
    }
    public int availableCapacity() { return capacity.availablePermits(); }
    @PreDestroy public void close() {
        executor.shutdownNow(); watchdog.shutdownNow();
        try { executor.awaitTermination(5,TimeUnit.SECONDS); watchdog.awaitTermination(1,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
