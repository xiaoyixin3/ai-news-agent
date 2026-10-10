package cn.zaozhi.scheduler;

import cn.zaozhi.*;
import cn.zaozhi.agent.TaskService;
import cn.zaozhi.controller.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class DailyScheduler {
    private final Settings settings;
    private final Database db;
    private final TaskService tasks;
    private final java.time.Clock clock;
    public DailyScheduler(Settings settings,Database db,TaskService tasks){this(settings,db,tasks,java.time.Clock.systemUTC());}
    @org.springframework.beans.factory.annotation.Autowired public DailyScheduler(Settings settings,Database db,TaskService tasks,java.time.Clock clock) { this.settings=settings; this.db=db; this.tasks=tasks;this.clock=clock; }
    @Scheduled(fixedDelay=20000,initialDelay=20000) public void scan() { scan(clock.instant()); }
    public void scan(Instant now) {
        if(!settings.scheduler||!settings.ready()) return;
        for(var user:db.all("SELECT id,profile FROM subscriptions")) {
            try {
                var p=Json.parse(user.path("profile").asText()); String date=Profile.dueDate(p,now);
                if(date==null||db.one("SELECT id FROM runs WHERE subscription_id=? AND schedule_date=?",user.path("id").asText(),date)!=null) continue;
                tasks.submit(user.path("id").asText(),p,"scheduled",date);
            } catch(ApiException e) { if(e.status==429) return; }
            catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(DailyScheduler.class).warn("跳过异常订阅，检查本地数据库或配置"); }
        }
    }
}
