package cn.zaozhi.subscription;

import cn.zaozhi.*;
import cn.zaozhi.controller.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {
    private final Database db;
    private final Settings settings;
    public SubscriptionService(Database db,Settings settings) { this.db = db; this.settings=settings; }
    private ObjectNode validate(JsonNode input){ObjectNode profile=Profile.validate(input);if(!settings.demo&&profile.path("channel").asText().equals("email")&&!settings.smtpReady())throw new ApiException(503,"邮件服务尚未配置，请选择站内收件箱，或由服务端配置 SMTP 后再订阅邮件");return profile;}
    public ObjectNode create(JsonNode input) { return db.createUser(validate(input)); }
    public ObjectNode authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 200) throw new ApiException(401, "请先保存订阅偏好");
        ObjectNode user = db.authenticate(header.substring(7));
        if (user == null) throw new ApiException(401, "访问令牌无效，请重新创建订阅");
        user.set("profile", Json.parse(user.path("profile").asText()));
        return user;
    }
    public ObjectNode update(String id, JsonNode input) {
        ObjectNode profile = validate(input);
        db.execute("UPDATE subscriptions SET profile=?,profile_version=profile_version+1,enabled=?,updated_at=? WHERE id=?", Json.stringify(profile),profile.path("enabled").asBoolean()?1:0,Database.now(), id);
        return Json.object("id", id, "profile", profile);
    }
}
