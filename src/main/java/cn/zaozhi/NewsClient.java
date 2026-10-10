package cn.zaozhi;

import com.fasterxml.jackson.databind.node.*;
import com.rometools.rome.feed.synd.*;
import com.rometools.rome.io.*;
import org.jsoup.Jsoup;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public class NewsClient {
    private static final Set<String> TRUSTED=Set.of("news.google.com","huggingface.co","arstechnica.com","openai.com","deepseek.com","anthropic.com","blog.google","research.google","microsoft.com","github.blog","techcrunch.com","technologyreview.com","venturebeat.com","reuters.com");
    public static final ArrayNode SOURCES=Json.arr().add(Json.object("name","Hugging Face Blog","url","https://huggingface.co/blog/feed.xml"))
        .add(Json.object("name","Ars Technica","url","https://feeds.arstechnica.com/arstechnica/technology-lab"))
        .add(Json.object("name","Google News","url","使用 search_news 按话题搜索"));

    public static String canonical(String value) {
        URI u=URI.create(value).normalize();
        if (!List.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=80 && u.getPort()!=443)) throw new IllegalArgumentException("仅支持公开 HTTP(S) 链接和标准端口");
        String query=u.getRawQuery();
        if (query!=null) query=Arrays.stream(query.split("&")).filter(x->!x.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*")).reduce((a,b)->a+"&"+b).orElse(null);
        return u.getScheme()+"://"+u.getRawAuthority().toLowerCase(Locale.ROOT)+(u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath())+(query==null||query.isEmpty()?"":"?"+query);
    }
    public static void validateTarget(String url) throws Exception {
        URI uri=URI.create(canonical(url)); String host=uri.getHost().toLowerCase(Locale.ROOT);
        validateDomain(host);
        for (InetAddress ip:InetAddress.getAllByName(host)) if (!publicAddress(ip)) throw new IllegalArgumentException("禁止访问私网、回环和保留地址");
    }
    public static void validateDomain(String host){String lower=host.toLowerCase(Locale.ROOT);if(TRUSTED.stream().noneMatch(d->lower.equals(d)||lower.endsWith("."+d)))throw new IllegalArgumentException("该域名不在新闻来源白名单");}
    public static boolean publicAddress(InetAddress ip) {
        if (ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()) return false;
        byte[] b=ip.getAddress(); int a=Byte.toUnsignedInt(b[0]);
        if (b.length==16) return (a&0xe0)==0x20 && !(a==0x20&&Byte.toUnsignedInt(b[1])==1&&Byte.toUnsignedInt(b[2])<2) && !(a==0x20&&Byte.toUnsignedInt(b[1])==1&&Byte.toUnsignedInt(b[2])==0x0d&&Byte.toUnsignedInt(b[3])==0xb8) && !(a==0x20&&Byte.toUnsignedInt(b[1])==2);
        int second=Byte.toUnsignedInt(b[1]);
        int third=Byte.toUnsignedInt(b[2]);return a!=0&&a!=127&&a<224&&!(a==100&&second>=64&&second<=127)&&!(a==198&&(second==18||second==19))&&!(a==192&&second==0)&&!(a==192&&second==0&&third==2)&&!(a==198&&second==51&&third==100)&&!(a==203&&second==0&&third==113);
    }
    public byte[] fetch(String url) throws Exception { return fetch(url,System.nanoTime()+TimeUnit.SECONDS.toNanos(20)); }
    public byte[] fetch(String url,long deadline) throws Exception {
        return new cn.zaozhi.news.SafeHttpFetcher(new Settings()).fetch(url,deadline,null);
    }
    public ArrayNode feed(String url,int hours) throws Exception { return parseFeed(fetch(url),url,hours,Instant.now()); }
    public ArrayNode feed(String url,int hours,long deadline) throws Exception { return parseFeed(fetch(url,deadline),url,hours,Instant.now()); }
    public ArrayNode feed(String url,cn.zaozhi.tool.ToolContext ctx)throws Exception{return parseFeed(new cn.zaozhi.news.SafeHttpFetcher(ctx.settings).fetch(url,ctx.deadline,ctx.budget),url,ctx.profile.path("lookback_hours").asInt(),ctx.windowEnd);}
    public static ArrayNode parseFeed(byte[] data,String url,int hours,Instant now) throws Exception {
        SyndFeedInput input=new SyndFeedInput(); input.setAllowDoctypes(false);
        SyndFeed feed=input.build(new XmlReader(new ByteArrayInputStream(data)));
        ArrayNode articles=Json.arr(); Set<String> used=new HashSet<>();
        int checked=0;
        for(SyndEntry entry:feed.getEntries()) {
            if (++checked>100 || articles.size()>=30) break;
            Date date=entry.getPublishedDate()!=null?entry.getPublishedDate():entry.getUpdatedDate();
            if(date==null) continue;
            long age=Duration.between(date.toInstant(),now).getSeconds();
            if(date.toInstant().isAfter(now)||age > hours*3600L) continue;
            String link;
            try { link=canonical(URI.create(url).resolve(entry.getLink()).toString()); } catch(Exception e) { continue; }
            if(entry.getTitle()==null || entry.getTitle().isBlank() || !used.add(link)) continue;
            String excerpt=entry.getDescription()==null?"":Jsoup.parse(entry.getDescription().getValue()).text();
            String source=entry.getSource()==null?feed.getTitle():entry.getSource().getTitle();
            articles.add(Json.object("source_id",Database.hash(link).substring(0,16),"title",clip(entry.getTitle(),400),"url",link,
                "source",clip(source,150),"published_at",date.toInstant().toString(),"excerpt",clip(excerpt,3000)));
        }
        return articles;
    }
    public String article(String url) throws Exception {
        return article(url,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
    }
    public String article(String url,long deadline) throws Exception {
        var doc=Jsoup.parse(new String(fetch(url,deadline),StandardCharsets.UTF_8));
        doc.select("script,style,nav,header,footer").remove();
        var main=doc.selectFirst("article,main");
        return clip(main==null?doc.text():main.text(),12000);
    }
    public String article(String url,cn.zaozhi.tool.ToolContext ctx)throws Exception{var doc=Jsoup.parse(new String(new cn.zaozhi.news.SafeHttpFetcher(ctx.settings).fetch(url,ctx.deadline,ctx.budget),StandardCharsets.UTF_8));doc.select("script,style,nav,header,footer").remove();var main=doc.selectFirst("article,main");return clip(main==null?doc.text():main.text(),100000);}
    public static String clip(String s,int max) { if(s==null)return "";return s.length()>max?s.substring(0,max):s; }
    public static ArrayNode demoNews() {
        ArrayNode a=Json.arr(); String now=Instant.now().toString();
        a.add(Json.object("source_id","demo-agent","title","演示：为 AI Agent 增加可追踪工具调用","url","https://example.com/demo/agent","source","离线演示资料","published_at",now,"excerpt","这是合成样例：一个 AI Agent 自主调用检索、读取和发布工具，并记录每次结果。"));
        a.add(Json.object("source_id","demo-model","title","演示：用订阅关键词筛选大模型动态","url","https://example.com/demo/model","source","离线演示资料","published_at",now,"excerpt","这是合成样例：根据大模型和 AI 应用关键词整理面向开发者的新闻摘要。"));
        a.add(Json.object("source_id","demo-research","title","演示：关注 AI 研究与应用落地","url","https://example.com/demo/research","source","离线演示资料","published_at",now,"excerpt","这是合成样例：为产品经理和研究者整理 AI 研究与应用的阅读线索。"));
        return a;
    }
}
