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
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
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
        if (TRUSTED.stream().noneMatch(d->host.equals(d)||host.endsWith("."+d))) throw new IllegalArgumentException("该域名不在新闻来源白名单；请换用官方 RSS 或 Google News 搜索");
        for (InetAddress ip:InetAddress.getAllByName(host)) if (!publicAddress(ip)) throw new IllegalArgumentException("禁止访问私网、回环和保留地址");
    }
    static boolean publicAddress(InetAddress ip) {
        if (ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()) return false;
        byte[] b=ip.getAddress(); int a=Byte.toUnsignedInt(b[0]);
        if (b.length==16) return (a&0xfe)!=0xfc && a!=0;
        int second=Byte.toUnsignedInt(b[1]);
        return a!=0 && a!=127 && a<224 && !(a==100 && second>=64 && second<=127) && !(a==198&&(second==18||second==19)) && !(a==192&&second==0);
    }
    public byte[] fetch(String url) throws Exception { return fetch(url,System.nanoTime()+TimeUnit.SECONDS.toNanos(20)); }
    public byte[] fetch(String url,long deadline) throws Exception {
        deadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        for(int redirects=0;redirects<5;redirects++) {
            url=canonical(url); validateTarget(url);
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            if(remaining<=0) throw new IOException("新闻来源访问超过时间上限");
            HttpRequest req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(remaining)).header("User-Agent","DailyAINewsAgent/1.0").GET().build();
            CompletableFuture<HttpResponse<byte[]>> future=http.sendAsync(req, info->new cn.zaozhi.news.BoundedBody(2_000_000));
            HttpResponse<byte[]> res;
            try { res=future.get(remaining,TimeUnit.MILLISECONDS); } catch(Exception e) { future.cancel(true); if(e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IOException("新闻来源超时或不可达",e); }
            if (Set.of(301,302,303,307,308).contains(res.statusCode())) { url=URI.create(url).resolve(res.headers().firstValue("location").orElseThrow()).toString(); continue; }
            if (res.statusCode()!=200) throw new IOException("新闻来源返回 HTTP "+res.statusCode());
            return res.body();
        }
        throw new IOException("来源重定向次数过多");
    }
    public ArrayNode feed(String url,int hours) throws Exception { return parseFeed(fetch(url),url,hours,Instant.now()); }
    public ArrayNode feed(String url,int hours,long deadline) throws Exception { return parseFeed(fetch(url,deadline),url,hours,Instant.now()); }
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
            if(age < -300 || age > hours*3600L) continue;
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
    public static String clip(String s,int max) { if(s==null)return "";return s.length()>max?s.substring(0,max):s; }
    public static ArrayNode demoNews() {
        ArrayNode a=Json.arr(); String now=Instant.now().toString();
        a.add(Json.object("source_id","demo-agent","title","演示：为 AI Agent 增加可追踪工具调用","url","https://example.com/demo/agent","source","离线演示资料","published_at",now,"excerpt","这是合成样例：一个 AI Agent 自主调用检索、读取和发布工具，并记录每次结果。"));
        a.add(Json.object("source_id","demo-model","title","演示：用订阅关键词筛选大模型动态","url","https://example.com/demo/model","source","离线演示资料","published_at",now,"excerpt","这是合成样例：根据大模型和 AI 应用关键词整理面向开发者的新闻摘要。"));
        a.add(Json.object("source_id","demo-research","title","演示：关注 AI 研究与应用落地","url","https://example.com/demo/research","source","离线演示资料","published_at",now,"excerpt","这是合成样例：为产品经理和研究者整理 AI 研究与应用的阅读线索。"));
        return a;
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private final int limit; private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(ByteBuffer chunk:chunks){
                if(out.size()+chunk.remaining()>limit){subscription.cancel();body.completeExceptionally(new IOException("新闻内容超出大小上限"));return;}
                byte[] bytes=new byte[chunk.remaining()];chunk.get(bytes);out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable t){body.completeExceptionally(t);}
        public void onComplete(){body.complete(out.toByteArray());}
    }
}
