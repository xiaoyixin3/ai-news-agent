package cn.zaozhi.controller;

import cn.zaozhi.*;
import cn.zaozhi.subscription.SubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SubscriptionController {
    private final SubscriptionService subscriptions;
    private final Settings settings;
    public SubscriptionController(SubscriptionService subscriptions, Settings settings) { this.subscriptions = subscriptions; this.settings = settings; }
    @GetMapping("/health") public ObjectNode health() {
        return Json.object("status", "ok", "mode", settings.mode(), "model", settings.model, "ready", settings.ready(), "scheduler", settings.scheduler,
            "smtp_ready", !settings.get("SMTP_HOST", "").isBlank() && !settings.get("SMTP_FROM", "").isBlank());
    }
    @PostMapping("/subscriptions") public ResponseEntity<ObjectNode> create(@RequestBody JsonNode input) { return ResponseEntity.status(201).body(subscriptions.create(input)); }
    @GetMapping("/me") public ObjectNode me(@RequestHeader(value="Authorization", required=false) String token) {
        ObjectNode user = subscriptions.authenticate(token);
        return Json.object("id", user.path("id"), "profile", user.path("profile"));
    }
    @PutMapping("/me") public ObjectNode update(@RequestHeader(value="Authorization", required=false) String token, @RequestBody JsonNode input) {
        return subscriptions.update(subscriptions.authenticate(token).path("id").asText(), input);
    }
}
