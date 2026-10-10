package cn.zaozhi.delivery;

import cn.zaozhi.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Component
public final class SmtpGateway implements MailGateway {
    private final Settings settings;
    public SmtpGateway(Settings settings) { this.settings=settings; }
    public void send(JsonNode brief,JsonNode profile) throws Exception {
        String host=settings.get("SMTP_HOST",""),from=settings.get("SMTP_FROM","");
        if(host.isBlank()||from.isBlank()) throw new MailFailure(MailFailure.Kind.PERMANENT,"SMTP_CONFIGURATION","未配置 SMTP_HOST 或 SMTP_FROM；简报已保存在站内");
        String tls=settings.get("SMTP_TLS","starttls");
        if(!tls.equals("starttls")&&!tls.equals("ssl")) throw new IllegalArgumentException("SMTP_TLS 仅支持 starttls 或 ssl");
        String user=settings.get("SMTP_USER",""),password=settings.get("SMTP_PASSWORD","");
        Properties p=new Properties();
        p.setProperty("mail.smtp.host",host); p.setProperty("mail.smtp.port",settings.get("SMTP_PORT",tls.equals("ssl")?"465":"587"));
        p.setProperty("mail.smtp.auth",String.valueOf(!user.isBlank()));
        p.setProperty("mail.smtp.connectiontimeout","10000"); p.setProperty("mail.smtp.timeout","10000"); p.setProperty("mail.smtp.writetimeout","10000");
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        if(tls.equals("ssl")) p.setProperty("mail.smtp.ssl.enable","true");
        else { p.setProperty("mail.smtp.starttls.enable","true"); p.setProperty("mail.smtp.starttls.required","true"); }
        Session session=Session.getInstance(p,!user.isBlank()?new Authenticator() { protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user,password); } }:null);
        String content=brief.path("intro").asText()+"\n\n";
        StringBuilder body=new StringBuilder(content);
        for(JsonNode item:brief.path("items")) body.append(item.path("title").asText()).append("\n").append(item.path("summary").asText()).append("\n与你相关：").append(item.path("why_it_matters").asText()).append("\n来源：").append(item.path("source").asText()).append("\n发布时间：").append(item.path("published_at").asText()).append("\n").append(item.path("url").asText()).append("\n\n");
        MimeMessage message=new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID","<brief-"+brief.path("id").asText()+"@daily-ai-news.local>"); }
        };
        message.setFrom(new InternetAddress(from,true));
        message.setRecipient(Message.RecipientType.TO,new InternetAddress(profile.path("email").asText(),true));
        message.setSubject(brief.path("title").asText(),StandardCharsets.UTF_8.name());
        message.setText(body.toString(),StandardCharsets.UTF_8.name());
        try {Transport.send(message);} catch(MessagingException e) {throw MailFailure.classify(e);}
    }
}
