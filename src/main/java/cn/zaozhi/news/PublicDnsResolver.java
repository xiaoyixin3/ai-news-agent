package cn.zaozhi.news;

import cn.zaozhi.*;
import cn.zaozhi.agent.RunBudget;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;

/** Optional trusted resolver at a fixed public IP. News addresses still pass the same policy. */
final class PublicDnsResolver {
 static InetAddress[] resolve(String host,long deadline,InetSocketAddress proxy,RunBudget budget)throws Exception {
  long millis=Math.min(5000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));if(millis<=0)throw new IOException("DNS 查询已到期限");
  var manager=PoolingHttpClientConnectionManagerBuilder.create().setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(millis)).setSocksProxyAddress(proxy).build()).build();
  var request=new HttpGet("https://1.1.1.1/dns-query?name="+URLEncoder.encode(host,StandardCharsets.UTF_8)+"&type=A");request.setHeader("Accept","application/dns-json");
  var timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"dns-http-deadline");t.setDaemon(true);return t;});var cancel=timer.schedule(request::cancel,millis,TimeUnit.MILLISECONDS);
  try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(millis)).setResponseTimeout(Timeout.ofMilliseconds(millis)).setConnectionRequestTimeout(Timeout.ofMilliseconds(millis)).build()).build()){
   return client.execute(request,response->{if(response.getCode()!=200||response.getEntity()==null)throw new IOException("公共 DNS 服务响应失败");try(var input=response.getEntity().getContent();var output=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1){if(budget!=null)budget.network(n);if(output.size()+n>65536)throw new IOException("DNS 响应过大");output.write(bytes,0,n);}return parse(new String(output.toByteArray(),StandardCharsets.UTF_8));}});
  }finally{cancel.cancel(false);timer.shutdownNow();}
 }
 static InetAddress[] parse(String text)throws IOException{
  var result=Json.parse(text);if(result.path("Status").asInt(-1)!=0||result.path("TC").asBoolean())throw new IOException("公共 DNS 未返回完整成功响应");
  var addresses=new LinkedHashSet<InetAddress>();for(var answer:result.path("Answer"))if(answer.path("type").asInt()==1){String value=answer.path("data").asText();if(!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw new IOException("DNS 返回非数字地址");String[] fields=value.split("\\.");byte[] raw=new byte[4];for(int i=0;i<4;i++){int part=Integer.parseInt(fields[i]);if(part>255)throw new IOException("DNS 地址无效");raw[i]=(byte)part;}addresses.add(InetAddress.getByAddress(raw));if(addresses.size()>16)throw new IOException("DNS 地址数量超限");}
  if(addresses.isEmpty())throw new IOException("DNS 无 IPv4 地址");return addresses.toArray(InetAddress[]::new);
 }
}
