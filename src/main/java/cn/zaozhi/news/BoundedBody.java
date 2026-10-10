package cn.zaozhi.news;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream out=new ByteArrayOutputStream();
    private final int limit;
    private Flow.Subscription subscription;
    public BoundedBody(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
    public void onNext(List<ByteBuffer> chunks) {
        for(ByteBuffer chunk:chunks) {
            if(out.size()+chunk.remaining()>limit) { subscription.cancel(); body.completeExceptionally(new IOException("响应内容超出大小上限")); return; }
            byte[] bytes=new byte[chunk.remaining()]; chunk.get(bytes); out.writeBytes(bytes);
        }
        subscription.request(1);
    }
    public void onError(Throwable t) { body.completeExceptionally(t); }
    public void onComplete() { body.complete(out.toByteArray()); }
}
