package com.camel_hub.advertisement.outreach.schedule;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;
public final class WeeklyScheduleScheduler {
    private final WeeklyScheduleService service;private final AtomicBoolean running=new AtomicBoolean();
    public WeeklyScheduleScheduler(WeeklyScheduleService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.outreach.weekly-poll-delay:PT30S}")
    void tick(){
        if(!running.compareAndSet(false,true))return;
        service.advanceOne().doFinally(ignored->running.set(false)).subscribe(ignored->{},error->LoggerFactory.getLogger(WeeklyScheduleScheduler.class).error("weekly_outreach_poll_failed category={}",error.getClass().getSimpleName()));
    }
}
