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
        var row=db.one("SELECT * FROM "+table+" WHERE id=? AND user_id=?",id,uid);
        if(row==null) throw new ApiException(404,"记录不存在");
        if(table.equals("briefs")) row.set("items",Json.parse(row.path("items").asText()));
        if(table.equals("runs")) row.set("preferences",Json.parse(row.path("preferences").asText()));
        return row;
    }
    @PostMapping("/runs") public ResponseEntity<ObjectNode> generate(@RequestHeader(value="Authorization",required=false) String token) {
        var u=user(token); String rid=tasks.submit(u.path("id").asText(),u.path("profile"),"manual",null);
        return ResponseEntity.accepted().body(Json.object("id",rid,"status","queued"));
    }
    @GetMapping("/runs") public ArrayNode runs(@RequestHeader(value="Authorization",required=false) String token) {
        var rows=db.all("SELECT id,status,trigger,schedule_date,mode,created_at,finished_at,error FROM runs WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 30",user(token).path("id").asText());
        return rows;
    }
    @GetMapping("/runs/{id}") public ObjectNode run(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) { return owned("runs",id,user(token).path("id").asText()); }
    @GetMapping("/runs/{id}/events") public ArrayNode events(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        owned("runs",id,user(token).path("id").asText());
        var rows=db.all("SELECT * FROM events WHERE run_id=? ORDER BY id",id);
        for(var row:rows) ((ObjectNode)row).set("payload",Json.parse(row.path("payload").asText()));
        return rows;
    }
    @GetMapping("/briefs") public ObjectNode briefs(@RequestHeader(value="Authorization",required=false) String token,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int limit) {
        if(page<1||page>100000||limit<1||limit>50) throw new IllegalArgumentException("分页参数无效");
        String uid=user(token).path("id").asText();
        return Json.object("items",db.history(uid,limit,(page-1)*limit),"page",page,"limit",limit,"total",db.one("SELECT COUNT(*) AS total FROM briefs WHERE user_id=?",uid).path("total"));
    }
    @GetMapping("/briefs/{id}") public ObjectNode brief(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) { return owned("briefs",id,user(token).path("id").asText()); }
    @PostMapping("/briefs/{id}/retry-delivery") public ResponseEntity<ObjectNode> retry(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        delivery.retry(user(token).path("id").asText(),id); return ResponseEntity.accepted().body(Json.object("status","sending"));
    }
}
