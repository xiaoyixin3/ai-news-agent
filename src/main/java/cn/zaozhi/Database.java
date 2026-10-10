package cn.zaozhi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class Database {
    private final String url;
    public Database(Path dir) {
        try {
            Files.createDirectories(dir); Class.forName("org.sqlite.JDBC");
            url = "jdbc:sqlite:" + dir.resolve("news.sqlite3");
            String sql;
            try (var in = getClass().getResourceAsStream("/migrations/001_initial.sql")) { sql = new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            try (Connection c = connect(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                boolean applied;
                try (ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version=1")) { applied=rs.getInt(1)>0; }
                if (!applied) {
                    c.setAutoCommit(false);
                    try {
                        for (String statement : sql.split(";")) if (!statement.isBlank() && !statement.strip().startsWith("PRAGMA")) st.execute(statement);
                        try (PreparedStatement migration=prepare(c,"INSERT INTO schema_migrations VALUES (1,?)",now())) { migration.executeUpdate(); }
                        c.commit();
                    } catch(Exception e) { c.rollback(); throw e; }
                }
            }
        } catch (Exception e) { throw new IllegalStateException("数据库初始化失败", e); }
    }
    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) { st.execute("PRAGMA foreign_keys=ON"); st.execute("PRAGMA busy_timeout=15000"); }
        return c;
    }
    private PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i=0; i<params.length; i++) st.setObject(i+1, params[i]);
        return st;
    }
    public synchronized int execute(String sql, Object... params) {
        try (Connection c = connect(); PreparedStatement st = prepare(c,sql,params)) { return st.executeUpdate(); }
        catch (SQLException e) { throw new IllegalStateException("数据库写入失败", e); }
    }
    public synchronized ArrayNode all(String sql, Object... params) {
        ArrayNode result = Json.arr();
        try (Connection c=connect(); PreparedStatement st=prepare(c,sql,params); ResultSet rs=st.executeQuery()) {
            while (rs.next()) {
                ObjectNode row=Json.obj(); ResultSetMetaData m=rs.getMetaData();
                for (int i=1;i<=m.getColumnCount();i++) row.set(m.getColumnLabel(i), Json.tree(rs.getObject(i)));
                result.add(row);
            }
            return result;
        } catch (SQLException e) { throw new IllegalStateException("数据库读取失败",e); }
    }
    public ObjectNode one(String sql, Object... params) { ArrayNode a=all(sql,params); return a.isEmpty()?null:(ObjectNode)a.get(0); }
    public static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    public static String now() { return Instant.now().toString(); }
    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public ObjectNode createUser(JsonNode profile) {
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), uid=id();
        execute("INSERT INTO subscriptions VALUES (?,?,?,?)",uid,hash(token),Json.stringify(profile),now());
        return Json.object("id",uid,"token",token,"profile",profile);
    }
    public ObjectNode authenticate(String token) { return one("SELECT * FROM subscriptions WHERE token_hash=?",hash(token)); }
    public synchronized String claim(String uid,JsonNode profile,String mode,String trigger,String date) {
        String rid=id();
        try (Connection c=connect(); PreparedStatement st=prepare(c,"INSERT INTO runs(id,user_id,status,trigger,schedule_date,mode,created_at,preferences) VALUES (?,?,?,?,?,?,?,?)",rid,uid,"queued",trigger,date,mode,now(),Json.stringify(profile))) { st.executeUpdate(); return rid; }
        catch (SQLException e) { if (e.getErrorCode()==19) return null; throw new IllegalStateException("无法创建任务",e); }
    }
    public void event(String rid,String kind,JsonNode payload) { execute("INSERT INTO events(run_id,created_at,kind,payload) VALUES (?,?,?,?)",rid,now(),kind,Json.stringify(payload)); }
    public ArrayNode history(String uid,int limit) {
        return history(uid,limit,0);
    }
    public ArrayNode history(String uid,int limit,int offset) {
        ArrayNode rows=all("SELECT * FROM briefs WHERE user_id=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",uid,limit,offset);
        for (JsonNode row:rows) ((ObjectNode)row).set("items",Json.parse(row.path("items").asText()));
        return rows;
    }
    public synchronized String finish(String rid,JsonNode brief) {
        String bid=id();
        try (Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement st=prepare(c,"INSERT INTO briefs(id,user_id,run_id,title,intro,items,mode,created_at) SELECT ?,user_id,id,?,?,?,mode,? FROM runs WHERE id=? AND status='running'",bid,brief.path("title").asText(),brief.path("intro").asText(),Json.stringify(brief.path("items")),now(),rid)) { if(st.executeUpdate()!=1) throw new IllegalStateException("任务状态不允许保存简报"); }
                try (PreparedStatement st=prepare(c,"UPDATE runs SET status='succeeded',finished_at=? WHERE id=? AND status='running'",now(),rid)) { if(st.executeUpdate()!=1) throw new IllegalStateException("任务已结束"); }
                c.commit(); return bid;
            } catch(Exception e) { c.rollback(); throw e; }
        } catch(Exception e) { throw new IllegalStateException("保存简报失败",e); }
    }
    public void recover() {
        execute("UPDATE runs SET status='failed',finished_at=?,error='服务重启，任务中断；请手动重新生成' WHERE status IN ('queued','running')",now());
        execute("UPDATE briefs SET delivery_status='failed',delivery_error='服务重启导致投递中断，远端可能已收信；请确认后重试' WHERE delivery_status='sending'");
    }
}
