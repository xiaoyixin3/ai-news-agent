package cn.zaozhi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
public class NewsAgentApplication {
    public static void main(String[] args) {
        if(java.util.Arrays.stream(args).anyMatch(a->a.startsWith("--maintenance="))){maintenance(args);return;}
        Settings settings = new Settings();
        SpringApplication app = new SpringApplication(NewsAgentApplication.class);
        app.setDefaultProperties(Map.of("server.port", settings.port));
        app.run(args);
    }
    private static void maintenance(String[] args){
        Map<String,String> options=new java.util.HashMap<>();for(String arg:args){int split=arg.indexOf('=');if(split>2)options.put(arg.substring(2,split),arg.substring(split+1));}
        try{String action=options.get("maintenance");java.nio.file.Path destination=java.nio.file.Path.of(java.util.Objects.requireNonNull(options.get("destination"),"需要 --destination")).toAbsolutePath().normalize();
            if(action.equals("restore")){Class.forName("org.sqlite.JDBC");cn.zaozhi.workspace.MaintenanceService.restore(java.nio.file.Path.of(java.util.Objects.requireNonNull(options.get("source"),"需要 --source")),destination);}
            else if(action.equals("backup")){Settings s=new Settings();try(var guard=new cn.zaozhi.config.SingleInstanceGuard(s.dataDir)){new cn.zaozhi.workspace.MaintenanceService(new Database(s)).backup(destination);}}
            else throw new IllegalArgumentException("maintenance 仅支持 backup 或 restore");System.out.println("完成："+destination);
        }catch(Exception e){System.err.println("维护操作失败："+e.getMessage());System.exit(1);}
    }
    @Bean Settings settings() { return new Settings(); }
    @Bean java.time.Clock clock(){return java.time.Clock.systemUTC();}
    @Bean(destroyMethod="close") cn.zaozhi.config.SingleInstanceGuard singleInstanceGuard(Settings settings) throws Exception {return new cn.zaozhi.config.SingleInstanceGuard(settings.dataDir);}
    @Bean Database database(Settings settings,cn.zaozhi.config.SingleInstanceGuard guard) { return new Database(settings); }
}
