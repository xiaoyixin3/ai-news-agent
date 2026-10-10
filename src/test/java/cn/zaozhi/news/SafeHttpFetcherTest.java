package cn.zaozhi.news;

import cn.zaozhi.*;
import cn.zaozhi.agent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SafeHttpFetcherTest {
    @TempDir Path dir;
    HttpServer server;
    @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();}
    @AfterEach void stop(){server.stop(0);}
    SafeHttpFetcher fixture(){return new SafeHttpFetcher(h->new InetAddress[]{InetAddress.getLoopbackAddress()},ip->ip.isLoopbackAddress(),server.getAddress().getPort());}
    long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
    @Test void actualConnectionUsesPinnedDnsWithoutSecondResolution()throws Exception {
        server.createContext("/ok",e->{byte[] body=e.getRequestHeaders().getFirst("Host").getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});AtomicInteger resolves=new AtomicInteger();
        var fetcher=new SafeHttpFetcher(h->{if(resolves.incrementAndGet()>1)throw new UnknownHostException("rebound");return new InetAddress[]{InetAddress.getLoopbackAddress()};},ip->ip.isLoopbackAddress(),server.getAddress().getPort());
        String body=new String(fetcher.fetch("http://huggingface.co/ok",deadline(),null));assertTrue(body.contains("huggingface.co"));assertEquals(1,resolves.get());
    }
    @Test void redirectsRecheckDomainAndAddressBeforeConnecting(){server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","http://127.0.0.1/private");e.sendResponseHeaders(302,-1);e.close();});assertThrows(IllegalArgumentException.class,()->fixture().fetch("http://huggingface.co/redirect",deadline(),null));var production=new SafeHttpFetcher(h->new InetAddress[]{InetAddress.getLoopbackAddress()},NewsClient::publicAddress);assertThrows(IllegalArgumentException.class,()->production.fetch("http://huggingface.co/ok",deadline(),null));}
    @Test void decompressionBombIsRejectedEvenWithSmallWirePayload()throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes)){gzip.write(new byte[2*1024*1024+1]);}server.createContext("/gzip",e->{e.getResponseHeaders().add("Content-Encoding","gzip");e.sendResponseHeaders(200,bytes.size());e.getResponseBody().write(bytes.toByteArray());e.close();});assertThrows(IOException.class,()->fixture().fetch("http://huggingface.co/gzip",deadline(),null));}
    @Test void cumulativeNetworkBudgetRejectsOtherwiseSmallResponse()throws Exception{server.createContext("/bytes",e->{byte[] b=new byte[2048];e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});var settings=new Settings(Map.of("DATA_DIR",dir.toString(),"NETWORK_BYTE_LIMIT","1000"));var db=new Database(settings);var p=Profile.validate(Json.object("nickname","budget"));var u=db.createUser(p);String rid=db.claim(u.path("id").asText(),p,"demo","manual",null);var budget=new RunBudget(settings,db,rid);assertThrows(AgentFailure.class,()->fixture().fetch("http://huggingface.co/bytes",deadline(),budget));assertTrue(db.one("SELECT network_bytes FROM runs WHERE id=?",rid).path("network_bytes").asLong()>1000);}
    @Test void fourthRedirectIsNotFollowed(){server.createContext("/loop",e->{e.getResponseHeaders().add("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});assertThrows(IOException.class,()->fixture().fetch("http://huggingface.co/loop",deadline(),null));}
    @Test void dnsWaitIsIncludedInAggregateDeadline(){var fetcher=new SafeHttpFetcher(h->{try{Thread.sleep(5000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}return new InetAddress[]{InetAddress.getLoopbackAddress()};},ip->true);long start=System.nanoTime();assertThrows(IOException.class,()->fetcher.fetch("http://huggingface.co/slow",start+TimeUnit.MILLISECONDS.toNanos(100),null));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);}
}
