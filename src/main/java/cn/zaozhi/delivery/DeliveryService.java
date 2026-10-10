package cn.zaozhi.delivery;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable outbox: wake-up is only an optimization; scheduled scanning recovers it. */
@Service
public class DeliveryService {
    private final Settings settings;private final Database db;private final MailGateway mail;
    private final ExecutorService sender=Executors.newSingleThreadExecutor(r->new Thread(r,"delivery-dispatcher"));
    private final AtomicBoolean busy=new AtomicBoolean();
    public DeliveryService(Settings settings,Database db,MailGateway mail){this.settings=settings;this.db=db;this.mail=mail;}
    @Scheduled(fixedDelay=5000,initialDelay=5000) public void wake(){if(settings.demo||!busy.compareAndSet(false,true))return;try{sender.execute(()->{try{dispatchDue();}finally{busy.set(false);}});}catch(RejectedExecutionException e){busy.set(false);}}
    public void dispatchDue(){if(settings.demo)return;for(var d:db.all("SELECT id FROM deliveries WHERE attempt_count<3 AND (status='pending' OR (status='failed' AND next_attempt_at_ms IS NOT NULL AND next_attempt_at_ms<=?)) ORDER BY created_at LIMIT 16",System.currentTimeMillis()))send(d.path("id").asText());}
    /** Kept for component-level tests; application generation uses wake(). */
    public void deliver(String bid,com.fasterxml.jackson.databind.JsonNode profile){if(settings.demo)return;var d=db.one("SELECT id FROM deliveries WHERE brief_id=?",bid);if(d!=null)send(d.path("id").asText());}
    private void send(String did){
        if(db.execute("UPDATE deliveries SET status='sending',attempt_count=attempt_count+1,next_attempt_at_ms=NULL,updated_at=? WHERE id=? AND attempt_count<3 AND (status='pending' OR (status='failed' AND next_attempt_at_ms IS NOT NULL AND next_attempt_at_ms<=?))",Database.now(),did,System.currentTimeMillis())!=1)return;
        var d=db.one("SELECT d.*,b.subscription_id FROM deliveries d JOIN briefs b ON b.id=d.brief_id WHERE d.id=?",did);
        var brief=db.brief(d.path("subscription_id").asText(),d.path("brief_id").asText());
        try{mail.send(brief,Json.object("channel","email","email",d.path("recipient")));db.execute("UPDATE deliveries SET status='sent',sent_at=?,updated_at=?,error_code=NULL,error_summary=NULL WHERE id=? AND status='sending'",Database.now(),Database.now(),did);}
        catch(Exception e){MailFailure failure=e instanceof MailFailure m?m:MailFailure.classify(e);String status=failure.kind==MailFailure.Kind.UNKNOWN?"unknown":"failed";Long next=failure.kind==MailFailure.Kind.TEMPORARY&&d.path("attempt_count").asInt()<3?System.currentTimeMillis()+Math.min(300000,30000L*(1L<<Math.max(0,d.path("attempt_count").asInt()-1))):null;
            db.execute("UPDATE deliveries SET status=?,next_attempt_at_ms=?,updated_at=?,error_code=?,error_summary=? WHERE id=? AND status='sending'",status,next,Database.now(),failure.code,settings.redact(NewsClient.clip(failure.getMessage(),512)),did);}
        db.event(brief.path("run_id").asText(),"delivery",Json.object("status",db.one("SELECT status FROM deliveries WHERE id=?",did).path("status")));
    }
    public ObjectNode detail(String uid,String bid){db.brief(uid,bid);var d=db.one("SELECT d.* FROM deliveries d JOIN briefs b ON b.id=d.brief_id WHERE d.brief_id=? AND b.subscription_id=?",bid,uid);if(d==null)throw new ApiException(404,"没有邮件投递任务");return d;}
    public void retry(String uid,String bid){retry(uid,bid,false);}
    public void retry(String uid,String bid,boolean confirmed){
        var d=detail(uid,bid);if(settings.demo)throw new ApiException(409,"演示模式不发送外部邮件");String state=d.path("status").asText();
        if(state.equals("unknown")&&!confirmed)throw new ApiException(409,"远端可能已收信，请明确确认接受重复风险后重试");
        if(!state.equals("failed")&&!state.equals("unknown"))throw new ApiException(409,"只能重试失败或结果不确定的投递");
        if(System.currentTimeMillis()-java.time.Instant.parse(d.path("updated_at").asText()).toEpochMilli()<settings.integer("DELIVERY_RETRY_COOLDOWN",30)*1000L)throw new ApiException(429,"请稍后再重试邮件");
        if(db.execute("UPDATE deliveries SET status='pending',retry_cycle=retry_cycle+1,attempt_count=0,next_attempt_at_ms=NULL,error_code=NULL,error_summary=NULL,updated_at=? WHERE id=? AND status=?",Database.now(),d.path("id").asText(),state)!=1)throw new ApiException(409,"投递状态已改变");wake();
    }
    public void cancel(String uid,String bid){var d=detail(uid,bid);if(db.execute("UPDATE deliveries SET status='cancelled',next_attempt_at_ms=NULL,updated_at=? WHERE id=? AND status IN ('pending','failed')",Database.now(),d.path("id").asText())!=1)throw new ApiException(409,"发送中、已发送或结果不确定的邮件不能取消");}
    @PreDestroy public void close(){sender.shutdown();try{if(!sender.awaitTermination(15,TimeUnit.SECONDS))sender.shutdownNow();}catch(InterruptedException e){sender.shutdownNow();Thread.currentThread().interrupt();}}
}
