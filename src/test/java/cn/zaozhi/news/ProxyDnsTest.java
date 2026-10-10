package cn.zaozhi.news;

import cn.zaozhi.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProxyDnsTest {
 @Test void socksReceivesPinnedNumericAddressAndOriginalHttpHost()throws Exception{
  try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
   server.setSoTimeout(5000);var executor=Executors.newSingleThreadExecutor();
   try{
    var received=executor.submit(()->{try(var socket=server.accept()){
     socket.setSoTimeout(5000);var in=new DataInputStream(socket.getInputStream());var out=socket.getOutputStream();
     assertEquals(5,in.readUnsignedByte());in.readNBytes(in.readUnsignedByte());out.write(new byte[]{5,0});out.flush();
     assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());
     assertEquals(1,in.readUnsignedByte(),"SOCKS must receive an IPv4 address, never a hostname");assertArrayEquals(new byte[]{8,8,8,8},in.readNBytes(4));assertEquals(80,in.readUnsignedShort());
     out.write(new byte[]{5,0,0,1,127,0,0,1,0,80});out.flush();
     var headers=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){headers.write(b);if(headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;if(headers.size()>8192)throw new IOException("headers too large");}
     out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));out.flush();return headers.toString(StandardCharsets.US_ASCII);
    }});
    var resolves=new AtomicInteger();var proxy=new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort());
    var fetcher=new SafeHttpFetcher(h->{resolves.incrementAndGet();return new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})};},NewsClient::publicAddress,0,proxy,false);
    assertEquals("OK",new String(fetcher.fetch("http://huggingface.co/test",System.nanoTime()+TimeUnit.SECONDS.toNanos(5),null),StandardCharsets.UTF_8));
    assertTrue(received.get(5,TimeUnit.SECONDS).toLowerCase(Locale.ROOT).contains("host: huggingface.co"));assertEquals(1,resolves.get());
   }finally{executor.shutdownNow();}
  }
 }
 @Test void privateDestinationRejectedBeforeProxyConnection()throws Exception{
  try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
   server.setSoTimeout(100);var fetcher=new SafeHttpFetcher(h->new InetAddress[]{InetAddress.getLoopbackAddress()},NewsClient::publicAddress,0,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),false);
   assertThrows(IllegalArgumentException.class,()->fetcher.fetch("http://huggingface.co/test",System.nanoTime()+TimeUnit.SECONDS.toNanos(2),null));assertThrows(SocketTimeoutException.class,server::accept);
  }
 }
 @Test void dnsParserDeduplicatesNumericAnswersAndIgnoresCname()throws Exception{
  var addresses=PublicDnsResolver.parse("{\"Status\":0,\"Answer\":[{\"type\":5,\"data\":\"cdn.example\"},{\"type\":1,\"data\":\"8.8.8.8\"},{\"type\":1,\"data\":\"8.8.8.8\"}]}");assertEquals(1,addresses.length);assertEquals("8.8.8.8",addresses[0].getHostAddress());
 }
 @Test void dnsParserRejectsFailedTruncatedAndMalformedAnswers(){
  for(String response:List.of("{\"Status\":2}","{\"Status\":0,\"TC\":true}","{\"Status\":0,\"Answer\":[{\"type\":1,\"data\":\"localhost\"}]}","{\"Status\":0,\"Answer\":[{\"type\":1,\"data\":\"256.1.1.1\"}]}"))assertThrows(IOException.class,()->PublicDnsResolver.parse(response));
 }
 @Test void proxyConfigurationRequiresExplicitLocalUnauthenticatedEndpoint(){
  assertNull(new Settings(Map.of()).newsProxy());assertTrue(new Settings(Map.of("NEWS_SOCKS_PROXY","socks5h://127.0.0.1:7897")).newsProxy().getAddress().isLoopbackAddress());
  for(String endpoint:List.of("socks5://8.8.8.8:80","socks5://user:pass@127.0.0.1:80","http://127.0.0.1:80","socks5://localhost","bad","socks5://127.0.0.1:80/path"))assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("NEWS_SOCKS_PROXY",endpoint)));
  assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("NEWS_DNS_MODE","arbitrary")));
 }
}
