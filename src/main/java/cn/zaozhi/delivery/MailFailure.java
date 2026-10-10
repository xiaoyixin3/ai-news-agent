package cn.zaozhi.delivery;

public final class MailFailure extends Exception {
    public enum Kind { TEMPORARY, PERMANENT, UNKNOWN }
    public final Kind kind;
    public final String code;
    public MailFailure(Kind kind,String code,String message){super(message);this.kind=kind;this.code=code;}
    public static MailFailure classify(Throwable e) {
        Throwable current=e;
        for(int i=0;i<12&&current!=null;i++,current=current.getCause()) {
            try {int reply=(int)current.getClass().getMethod("getReturnCode").invoke(current);if(reply>=400&&reply<600)return new MailFailure(reply<500?Kind.TEMPORARY:Kind.PERMANENT,"SMTP_"+reply,"邮件服务器明确拒绝："+reply);}catch(Exception ignored){}
            if(current instanceof jakarta.mail.AuthenticationFailedException)return new MailFailure(Kind.PERMANENT,"SMTP_AUTH","邮件认证失败，请检查本地配置");
            if(current instanceof java.net.ConnectException||current instanceof java.net.UnknownHostException)return new MailFailure(Kind.TEMPORARY,"SMTP_CONNECT","无法连接邮件服务器");
        }
        return new MailFailure(Kind.UNKNOWN,"SMTP_UNCERTAIN","发送结果不确定，请确认是否已收信后再重试");
    }
}
