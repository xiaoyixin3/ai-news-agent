package cn.zaozhi.agent;
public final class AgentFailure extends IllegalStateException {
    public final String code;
    public AgentFailure(String code,String message){super(message);this.code=code;}
}
