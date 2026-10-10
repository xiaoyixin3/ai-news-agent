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
        Settings settings = new Settings();
        SpringApplication app = new SpringApplication(NewsAgentApplication.class);
        app.setDefaultProperties(Map.of("server.port", settings.port));
        app.run(args);
    }
    @Bean Settings settings() { return new Settings(); }
    @Bean Database database(Settings settings) { return new Database(settings.dataDir); }
}
