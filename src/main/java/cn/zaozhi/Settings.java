package cn.zaozhi;

import java.nio.file.*;
import java.util.*;

public final class Settings {
    private final Map<String,String> values;
    public final Path dataDir;
    public final boolean demo, scheduler;
    public final String apiKey, baseUrl, model;
    public final int port, maxRounds, maxCalls, runTimeout, llmTimeout;
    public Settings() { this(load()); }
    public Settings(Map<String,String> values) {
        this.values = new HashMap<>(values);
        dataDir = Path.of(get("DATA_DIR", "data")).toAbsolutePath().normalize();
        demo = Boolean.parseBoolean(get("DEMO_MODE", "true"));
        scheduler = Boolean.parseBoolean(get("SCHEDULER_ENABLED", "true"));
        apiKey = get("DEEPSEEK_API_KEY", ""); baseUrl = get("LLM_BASE_URL", "https://api.deepseek.com").replaceAll("/+$", "");
        model = get("LLM_MODEL", "deepseek-flash"); port = integer("PORT", 8080);
        maxRounds = integer("MAX_ROUNDS", 16); maxCalls = integer("MAX_TOOL_CALLS", 40);
        runTimeout = integer("RUN_TIMEOUT", 300); llmTimeout = integer("LLM_TIMEOUT", 60);
    }
    public String get(String name, String fallback) { return values.getOrDefault(name, fallback); }
    public int integer(String name, int fallback) { return Integer.parseInt(get(name, "" + fallback)); }
    public boolean ready() { return demo || !apiKey.isBlank(); }
    public String mode() { return demo ? "demo" : "live"; }
    public String redact(String value) {
        for (String secret : List.of(apiKey, get("SMTP_PASSWORD", "")))
            if (!secret.isEmpty()) value = value.replace(secret, "[REDACTED]");
        return value;
    }
    private static Map<String,String> load() {
        Map<String,String> v = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) try {
            for (String line : Files.readAllLines(file)) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#") || !line.contains("=")) continue;
                int equal = line.indexOf('=');
                String value = line.substring(equal + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1,value.length()-1);
                v.put(line.substring(0,equal).strip(), value);
            }
        } catch (Exception e) { throw new IllegalStateException("无法读取 .env", e); }
        v.putAll(System.getenv()); // Process environment always takes precedence.
        return v;
    }
}
