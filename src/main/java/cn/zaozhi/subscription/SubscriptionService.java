package cn.zaozhi.subscription;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {
    private final Database db;
    public SubscriptionService(Database db) { this.db = db; }
    public ObjectNode create(JsonNode input) { return db.createUser(Profile.validate(input)); }
    public ObjectNode authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 200) throw new ApiException(401, "请先保存订阅偏好");
        ObjectNode user = db.authenticate(header.substring(7));
        if (user == null) throw new ApiException(401, "访问令牌无效，请重新创建订阅");
        user.set("profile", Json.parse(user.path("profile").asText()));
        return user;
    }
    public ObjectNode update(String id, JsonNode input) {
        ObjectNode profile = Profile.validate(input);
        db.execute("UPDATE subscriptions SET profile=?,profile_version=profile_version+1,enabled=?,updated_at=? WHERE id=?", Json.stringify(profile),profile.path("enabled").asBoolean()?1:0,Database.now(), id);
        return Json.object("id", id, "profile", profile);
    }
}
