package cn.zaozhi.delivery;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

@Service
public class DeliveryService {
    private final Settings settings;
    private final Database db;
    private final MailGateway mail;
    private final ThreadPoolExecutor retries=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->new Thread(r,"mail-retry"),new ThreadPoolExecutor.AbortPolicy());
    public DeliveryService(Settings settings,Database db,MailGateway mail) { this.settings=settings; this.db=db; this.mail=mail; }
    public void deliver(String bid,JsonNode profile) {
        if(settings.demo) { db.execute("UPDATE briefs SET delivery_status='demo_inbox' WHERE id=?",bid); return; }
        if(!profile.path("channel").asText().equals("email")) return;
        if(db.execute("UPDATE briefs SET delivery_status='sending' WHERE id=? AND delivery_status='inbox'",bid)==1) sendClaimed(bid,profile);
    }
    public void retry(String uid,String bid) {
        ObjectNode brief=db.one("SELECT * FROM briefs WHERE id=? AND user_id=?",bid,uid);
        if(brief==null) throw new ApiException(404,"简报不存在");
        if(settings.demo || !brief.path("mode").asText().equals("live")) throw new ApiException(409,"演示简报不发送邮件");
        var run=db.one("SELECT preferences FROM runs WHERE id=?",brief.path("run_id").asText());
        JsonNode profile=Json.parse(run.path("preferences").asText());
        if(!profile.path("channel").asText().equals("email")) throw new ApiException(409,"这份简报未选择邮件渠道");
        if(db.execute("UPDATE briefs SET delivery_status='sending',delivery_error=NULL WHERE id=? AND user_id=? AND delivery_status='failed'",bid,uid)!=1) throw new ApiException(409,"只能重试失败的邮件，正在发送或已发送的邮件不能重复发送");
        try { retries.execute(()->sendClaimed(bid,profile)); }
        catch(RejectedExecutionException e) { db.execute("UPDATE briefs SET delivery_status='failed',delivery_error='邮件重试队列已满' WHERE id=? AND delivery_status='sending'",bid); throw new ApiException(429,"邮件重试队列已满，请稍后重试"); }
    }
    private void sendClaimed(String bid,JsonNode profile) {
        var brief=db.one("SELECT * FROM briefs WHERE id=?",bid); brief.set("items",Json.parse(brief.path("items").asText()));
        try { mail.send(brief,profile); db.execute("UPDATE briefs SET delivery_status='sent',delivery_error=NULL WHERE id=?",bid); }
        catch(Exception e) { db.execute("UPDATE briefs SET delivery_status='failed',delivery_error=? WHERE id=?",settings.redact(NewsClient.clip(e.getMessage(),300)),bid); }
        db.event(brief.path("run_id").asText(),"delivery",Json.object("status",db.one("SELECT delivery_status FROM briefs WHERE id=?",bid).path("delivery_status")));
    }
    @PreDestroy public void close() {
        retries.shutdownNow();
        try { retries.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
