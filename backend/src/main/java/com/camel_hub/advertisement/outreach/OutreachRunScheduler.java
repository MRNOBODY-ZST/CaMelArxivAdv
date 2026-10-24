package com.camel_hub.advertisement.outreach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OutreachRunScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(OutreachRunScheduler.class);
    private final OutreachRunService service;
    private final AtomicBoolean running=new AtomicBoolean();
    public OutreachRunScheduler(OutreachRunService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.outreach.poll-delay:PT1S}")
    void tick() {
        if(!running.compareAndSet(false,true)) return;
        service.advanceOne().doFinally(ignored->running.set(false)).subscribe(ignored->{},
                error->LOGGER.error("outreach_poll_failed category={}",error.getClass().getSimpleName()));
    }
}
