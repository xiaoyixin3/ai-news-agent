package cn.zaozhi;

import cn.zaozhi.subscription.SubscriptionService;
import cn.zaozhi.controller.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FoundationTest {
    @TempDir Path dir;
    @Test void persistsSubscriptionAndOnlyHashOfToken() {
        Database db=new Database(dir);
        SubscriptionService service=new SubscriptionService(db,new Settings(Map.of("DATA_DIR",dir.toString())));
        var created=service.create(Json.object("nickname","测试开发者"));
        var user=service.authenticate("Bearer "+created.path("token").asText());
        assertEquals("测试开发者",user.path("profile").path("nickname").asText());
        assertNotEquals(created.path("token").asText(),user.path("token_hash").asText());
        assertThrows(ApiException.class,()->service.authenticate("Bearer wrong"));
        assertNotNull(new Database(dir).authenticate(created.path("token").asText()));
        assertEquals(3,db.one("SELECT COUNT(*) AS count FROM schema_migrations").path("count").asInt());
    }
    @Test void profileEnforcesTypesTimezoneAndRanges() {
        assertThrows(IllegalArgumentException.class,()->Profile.validate(Json.object("nickname","x","max_items",11)));
        assertThrows(IllegalArgumentException.class,()->Profile.validate(Json.object("nickname","x","timezone","wrong")));
        assertThrows(IllegalArgumentException.class,()->Profile.validate(Json.object("nickname","x","enabled","yes")));
        var p=Profile.validate(Json.object("nickname","x","time","09:00"));
        assertNull(Profile.dueDate(p,Instant.parse("2026-10-10T00:59:00Z")));
        assertEquals("2026-10-10",Profile.dueDate(p,Instant.parse("2026-10-10T01:00:00Z")));
    }
}
