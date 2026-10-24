package com.camel_hub.advertisement.outreach.bulk;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BulkOutreachScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(BulkOutreachScheduler.class);
    private final BulkOutreachService service;
    private final AtomicBoolean running=new AtomicBoolean();
    public BulkOutreachScheduler(BulkOutreachService service) {this.service=service;}
    @Scheduled(fixedDelayString="${app.outreach.poll-delay:PT1S}")
    public void tick() {
        if(!running.compareAndSet(false,true)) return;
        service.advanceOne().doFinally(ignored->running.set(false)).subscribe(ignored->{},error->LOGGER.error("bulk_outreach_poll_failed category={}",error.getClass().getSimpleName()));
    }
}
