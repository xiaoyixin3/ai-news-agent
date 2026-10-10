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
        demo = bool("DEMO_MODE",true);
        scheduler = bool("SCHEDULER_ENABLED",true);
        apiKey = get("DEEPSEEK_API_KEY", ""); baseUrl = get("LLM_BASE_URL", "https://api.deepseek.com").replaceAll("/+$", "");
        model = get("LLM_MODEL", "deepseek-flash"); port = integer("PORT", 8080);
        maxRounds = integer("MAX_ROUNDS", 16); maxCalls = integer("MAX_TOOL_CALLS", 40);
        runTimeout = integer("RUN_TIMEOUT", 300); llmTimeout = integer("LLM_TIMEOUT", 60);
        if(port<1||port>65535||maxRounds<1||maxRounds>100||maxCalls<1||maxCalls>200||runTimeout<1||runTimeout>1800||llmTimeout<1||llmTimeout>300) throw new IllegalArgumentException("环境配置超出允许范围");
        limit("CONTEXT_TOKEN_LIMIT",24000,64,128000);limit("RUN_TOKEN_LIMIT",100000,1000,1000000);limit("NETWORK_BYTE_LIMIT",20*1024*1024,1,100*1024*1024);limit("WORKSPACE_BYTE_LIMIT",10*1024*1024,64,50*1024*1024);limit("DELIVERY_RETRY_COOLDOWN",30,0,600);
        java.net.URI base=java.net.URI.create(baseUrl);
        if(!List.of("https","http").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null) throw new IllegalArgumentException("LLM_BASE_URL 必须是有效 HTTP(S) 地址");
    }
    public String get(String name, String fallback) { return values.getOrDefault(name, fallback); }
    public int integer(String name, int fallback) { return Integer.parseInt(get(name, "" + fallback)); }
    private void limit(String name,int fallback,int min,int max){int n=integer(name,fallback);if(n<min||n>max)throw new IllegalArgumentException(name+" 超出允许范围");}
    private boolean bool(String name,boolean fallback) {
        String value=get(name,String.valueOf(fallback));
        if(!value.equals("true")&&!value.equals("false")) throw new IllegalArgumentException(name+" 必须为 true 或 false");
        return Boolean.parseBoolean(value);
    }
    public boolean ready() { return demo || !apiKey.isBlank(); }
    public String mode() { return demo ? "demo" : "live"; }
    public com.fasterxml.jackson.databind.node.ObjectNode snapshot() {
        return Json.object("model",model,"base_url",baseUrl,"prompt_version","news-1.1","tool_schema_version","1.1",
            "max_rounds",maxRounds,"max_calls",maxCalls,"run_timeout",runTimeout,"llm_timeout",llmTimeout,
            "context_tokens",integer("CONTEXT_TOKEN_LIMIT",24000),"run_tokens",integer("RUN_TOKEN_LIMIT",100000),"network_bytes",integer("NETWORK_BYTE_LIMIT",20*1024*1024),"workspace_bytes",integer("WORKSPACE_BYTE_LIMIT",10*1024*1024));
    }
    public String redact(String value) {
        if(value==null) return "执行失败，请检查配置或稍后重试";
        for (String secret : List.of(apiKey, get("SMTP_PASSWORD", "")))
            if (!secret.isEmpty()) value = value.replace(secret, "[REDACTED]");
        return value.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[EMAIL]");
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
