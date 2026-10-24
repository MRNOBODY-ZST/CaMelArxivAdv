package com.camel_hub.advertisement.arxiv.extraction.admission;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;

/** Bounded, default-disabled internal Redis mailbox. Never logs frames, scope or result data. */
public final class SourceAdmissionMailbox implements SmartLifecycle {
    private static final DefaultRedisScript<Long> REMOVE=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",Long.class);
    private final SourceAdmissionService service;
    private final ReactiveStringRedisTemplate redis;
    private volatile Disposable polling;
    private volatile boolean running;
    private Disposable.Composite handlers=Disposables.composite();
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(4);
    private int cursor;
    public SourceAdmissionMailbox(SourceAdmissionService service,ReactiveStringRedisTemplate redis) { this.service=service; this.redis=redis; }

    Mono<Void> pollSlot(int slot) {
        if(slot<0 || slot>=64) return Mono.error(new IllegalArgumentException("INVALID_SLOT"));
        String key="camel:worker:source-admission:{source-admission}:slot:"+slot;
        return read(key).flatMap(frame->handle(key,frame));
    }
    private Mono<String> read(String key) {
        return redis.execute(connection->connection.stringCommands().get(java.nio.ByteBuffer.wrap(SourceCommandIdentity.utf8(key))))
                .singleOrEmpty().map(bytes->{
                    SourceCommandIdentity.require(bytes.remaining()<=4096);
                    return SourceAdmissionDtos.strictUtf8(bytes);
                }).timeout(Duration.ofSeconds(1));
    }
    private Mono<Void> handle(String key,String frame) {
        return Mono.defer(()->{
            final AdmissionRequest request;
            try { request=decodeRequest(frame); if(!slotKey(request.nonce()).equals(key)) return remove(key,frame).then(); }
            catch(IllegalArgumentException invalid) { return remove(key,frame).then(); }
            return remove(key,frame).flatMap(claimed->claimed!=1?Mono.empty():service.execute(request)
                    // execute emits only after its owned transaction has really committed.
                    .flatMap(reply->publish(request,reply)));
        }).timeout(Duration.ofSeconds(10));
    }
    Mono<Long> remove(String key,String frame) { return redis.execute(REMOVE,List.of(key),frame).single(); }
    private Mono<Void> publish(AdmissionRequest request,AdmissionReply reply) {
        String key=replyKey(request.nonce()), value=encodeReply(reply);
        return redis.opsForValue().setIfAbsent(key,value,Duration.ofSeconds(20)).flatMap(acknowledged->{
            if(Boolean.TRUE.equals(acknowledged)) return Mono.empty();
            // An immutable original reply is allowed; never overwrite or extend its deadline.
            return redis.opsForValue().get(key).filter(value::equals).switchIfEmpty(Mono.error(new IllegalStateException("REPLY_NOT_ACKNOWLEDGED"))).then();
        });
    }
    @Override public synchronized void start() {
        if(isRunning()) return;
        running=true;
        handlers=Disposables.composite();
        cursor=0;
        polling=Flux.interval(Duration.ZERO,Duration.ofMillis(250)).onBackpressureDrop()
                .flatMap(tick->{
                    int first=cursor;
                    cursor=(cursor+8)%64; // Advance on accepted batches, not dropped timer ticks.
                    return Flux.range(0,8).flatMap(offset->{
                        String key="camel:worker:source-admission:{source-admission}:slot:"+((first+offset)%64);
                        return read(key).doOnNext(frame->dispatch(key,frame)).onErrorResume(e->Mono.empty());
                    },8).then();
                },1,1)
                .subscribe(ignored->{},ignored->{});
    }

    /** Lifecycle-owned subscriptions: four slots, no request queue and no claim without capacity. */
    private synchronized void dispatch(String key,String frame) {
        if(!running || !capacity.tryAcquire()) return;
        Disposable.Composite group=handlers;
        Disposable.Swap task=Disposables.swap();
        group.add(task);
        task.update(handle(key,frame).doFinally(signal->{
            capacity.release();
            group.remove(task);
        }).subscribe(ignored->{},ignored->{}));
    }
    @Override public synchronized void stop() {
        running=false;
        if(polling!=null) { polling.dispose(); polling=null; }
        handlers.dispose();
    }
    @Override public boolean isRunning() { return running && polling!=null && !polling.isDisposed(); }
}
