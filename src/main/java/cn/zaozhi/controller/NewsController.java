package cn.zaozhi.controller;

import cn.zaozhi.*;
import cn.zaozhi.agent.TaskService;
import cn.zaozhi.delivery.DeliveryService;
import cn.zaozhi.subscription.SubscriptionService;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NewsController {
    private final Database db;
    private final SubscriptionService subscriptions;
    private final TaskService tasks;
    private final DeliveryService delivery;
    public NewsController(Database db,SubscriptionService subscriptions,TaskService tasks,DeliveryService delivery) { this.db=db; this.subscriptions=subscriptions; this.tasks=tasks; this.delivery=delivery; }
    private ObjectNode user(String token) { return subscriptions.authenticate(token); }
    private ObjectNode owned(String table,String id,String uid) {
        if(table.equals("briefs"))return db.brief(uid,id);
        var row=db.one("SELECT * FROM "+table+" WHERE id=? AND subscription_id=?",id,uid);
        if(row==null) throw new ApiException(404,"记录不存在");
        if(table.equals("briefs")) row.set("items",Json.parse(row.path("items").asText()));
        if(table.equals("runs")) row.set("preferences",Json.parse(row.path("preferences").asText()));
        return row;
    }
    @PostMapping("/runs") public ResponseEntity<ObjectNode> generate(@RequestHeader(value="Authorization",required=false) String token,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) com.fasterxml.jackson.databind.JsonNode body) {
        var request=body==null?Json.obj():body;if(!request.isObject())throw new IllegalArgumentException("任务请求应为对象");request.fieldNames().forEachRemaining(k->{if(!k.equals("retry_of"))throw new IllegalArgumentException("未知任务字段");});
        var u=user(token);String retry=request.path("retry_of").asText(null); String rid=tasks.submit(u.path("id").asText(),retry==null?"manual":"retry",null,key,key==null?null:Database.hash(Json.stringify(request)),retry);
        return ResponseEntity.accepted().body(Json.object("id",rid,"status","queued"));
    }
    @GetMapping("/runs") public ArrayNode runs(@RequestHeader(value="Authorization",required=false) String token) {
        var rows=db.all("SELECT id,status,trigger,schedule_date,mode,created_at,finished_at,error FROM runs WHERE subscription_id=? ORDER BY created_at DESC,id DESC LIMIT 30",user(token).path("id").asText());
        return rows;
    }
    @GetMapping("/runs/{id}") public ObjectNode run(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) { return owned("runs",id,user(token).path("id").asText()); }
    @GetMapping("/runs/{id}/events") public ArrayNode events(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        return db.events(user(token).path("id").asText(),id);
    }
    @GetMapping("/briefs") public ObjectNode briefs(@RequestHeader(value="Authorization",required=false) String token,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int limit,@RequestParam(required=false) String mode) {
        if(page<1||page>100000||limit<1||limit>50) throw new IllegalArgumentException("分页参数无效");
        String uid=user(token).path("id").asText();
        if(mode!=null&&!java.util.Set.of("demo","live").contains(mode))throw new IllegalArgumentException("模式无效");
        return Json.object("items",db.history(uid,mode,limit,(page-1)*limit),"page",page,"limit",limit,"total",mode==null?db.one("SELECT COUNT(*) AS total FROM briefs WHERE subscription_id=?",uid).path("total"):db.one("SELECT COUNT(*) AS total FROM briefs WHERE subscription_id=? AND mode=?",uid,mode).path("total"));
    }
    @GetMapping("/briefs/{id}") public ObjectNode brief(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) { return owned("briefs",id,user(token).path("id").asText()); }
    @PostMapping("/briefs/{id}/retry-delivery") public ResponseEntity<ObjectNode> retry(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestParam(defaultValue="false") boolean confirmDuplicateRisk) {
        delivery.retry(user(token).path("id").asText(),id,confirmDuplicateRisk); return ResponseEntity.accepted().body(Json.object("status","pending"));
    }
    @PostMapping("/runs/{id}/cancel") public ObjectNode cancelRun(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id){tasks.cancel(user(token).path("id").asText(),id);return Json.object("status","cancelled");}
    @GetMapping("/briefs/{id}/delivery") public ObjectNode deliveryDetail(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id){return delivery.detail(user(token).path("id").asText(),id);}
    @PostMapping("/briefs/{id}/cancel-delivery") public ObjectNode cancelDelivery(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id){delivery.cancel(user(token).path("id").asText(),id);return Json.object("status","cancelled");}
}
