package cn.zaozhi.news;

import cn.zaozhi.*;
import cn.zaozhi.agent.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.zip.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;

/** DNS is resolved and checked once per hop, then pinned in the transport. */
public final class SafeHttpFetcher {
    private static final ExecutorService DNS_WORKERS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"bounded-news-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    @FunctionalInterface interface Resolver {InetAddress[] resolve(String host)throws UnknownHostException;}
    private final Resolver resolver;private final Predicate<InetAddress> policy;private final int fixturePort;private final InetSocketAddress proxy;private final boolean publicDns;
    public SafeHttpFetcher(){this(InetAddress::getAllByName,NewsClient::publicAddress);}
    public SafeHttpFetcher(Settings settings){this(InetAddress::getAllByName,NewsClient::publicAddress,0,settings.newsProxy(),settings.get("NEWS_DNS_MODE","system").equals("cloudflare"));}
    SafeHttpFetcher(Resolver resolver,Predicate<InetAddress> policy){this(resolver,policy,0);}
    SafeHttpFetcher(Resolver resolver,Predicate<InetAddress> policy,int fixturePort){this(resolver,policy,fixturePort,null,false);}
    SafeHttpFetcher(Resolver resolver,Predicate<InetAddress> policy,int fixturePort,InetSocketAddress proxy,boolean publicDns){this.resolver=resolver;this.policy=policy;this.fixturePort=fixturePort;this.proxy=proxy;this.publicDns=publicDns;}
    public byte[] fetch(String value,long deadline,RunBudget budget)throws Exception {
        long end=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));String current=value;
        for(int hops=0;hops<=3;hops++) {
            URI uri=URI.create(NewsClient.canonical(current));String host=uri.getHost();NewsClient.validateDomain(host);
            Future<InetAddress[]> resolution;
            try{resolution=DNS_WORKERS.submit(()->publicDns?PublicDnsResolver.resolve(host,end,proxy,budget):resolver.resolve(host));}catch(RejectedExecutionException busy){throw new IOException("DNS 查询队列已满",busy);}
            InetAddress[] pinned;try{pinned=resolution.get(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(TimeoutException late){throw new IOException("DNS 查询超过请求期限",late);}catch(ExecutionException failure){throw new IOException("DNS 查询失败",failure.getCause());}finally{resolution.cancel(true);}
            if(pinned.length==0)throw new IllegalArgumentException("DNS 无有效地址");for(InetAddress ip:pinned)if(!policy.test(ip))throw new IllegalArgumentException("禁止连接非公网地址");
            long remaining=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());if(remaining<=0||Thread.currentThread().isInterrupted())throw new AgentFailure("DEADLINE_EXCEEDED","新闻请求超过期限");
            DnsResolver dns=new DnsResolver(){public InetAddress[] resolve(String requested)throws UnknownHostException{if(!host.equalsIgnoreCase(requested))throw new UnknownHostException("未授权域名");return pinned.clone();}public String resolveCanonicalHostname(String requested)throws UnknownHostException{if(!host.equalsIgnoreCase(requested))throw new UnknownHostException();return host;}};
            var builder=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns).setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(remaining)).setSocksProxyAddress(proxy).build());
            if(fixturePort>0)builder.setSchemePortResolver(h->fixturePort);
            var manager=builder.build();
            HttpGet request=new HttpGet(uri);request.setHeader("User-Agent","DailyAINewsAgent/1.1");request.setHeader("Accept-Encoding","gzip, deflate");
            // Cancel the actual request at the aggregate deadline, not just each read.
            ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"news-fetch-deadline");t.setDaemon(true);return t;});
            ScheduledFuture<?> cancel=timer.schedule(request::cancel,remaining,TimeUnit.MILLISECONDS);
            try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(remaining)).setConnectTimeout(Timeout.ofMilliseconds(remaining)).setResponseTimeout(Timeout.ofMilliseconds(remaining)).build()).build()) {
                var response=client.execute(request,res->{
                    int code=res.getCode();if(code>=300&&code<400){var location=res.getFirstHeader("Location");if(location==null)throw new IOException("重定向缺少 Location");if(res.getEntity()!=null)try(InputStream body=res.getEntity().getContent()){byte[] buffer=new byte[8192];long total=0;int n;while((n=body.read(buffer))!=-1){total+=n;if(budget!=null)budget.network(n);if(total>2*1024*1024||System.nanoTime()>=end)throw new IOException("重定向响应超出资源上限");}}return new FetchResult(code,location.getValue(),null);}
                    if(code!=200)throw new IOException("新闻来源返回 HTTP "+code);if(res.getEntity()==null)return new FetchResult(200,null,new byte[0]);
                    try(InputStream original=res.getEntity().getContent()) {
                        InputStream counted=new FilterInputStream(original){long raw;public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);if(n>0){raw+=n;if(budget!=null)budget.network(n);if(raw>2*1024*1024)throw new IOException("传输响应超过 2 MiB");}return n;}};
                        String encoding=res.getEntity().getContentEncoding();InputStream decoded=encoding==null?counted:encoding.equalsIgnoreCase("gzip")?new GZIPInputStream(counted):encoding.equalsIgnoreCase("deflate")?new InflaterInputStream(counted):null;if(decoded==null)throw new IOException("不支持的压缩格式");
                        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=decoded.read(buffer))!=-1){if(System.nanoTime()>=end||Thread.currentThread().isInterrupted())throw new IOException("新闻读取超过期限");if(out.size()+n>2*1024*1024)throw new IOException("解压后响应超过 2 MiB");out.write(buffer,0,n);}return new FetchResult(200,null,out.toByteArray());
                    }
                });
                if(response.code==200)return response.body;if(hops==3)throw new IOException("超过三跳重定向上限");current=uri.resolve(response.location).toString();
            }finally{cancel.cancel(false);timer.shutdownNow();}
        }throw new IOException("来源访问失败");
    }
    private record FetchResult(int code,String location,byte[] body){}
}
