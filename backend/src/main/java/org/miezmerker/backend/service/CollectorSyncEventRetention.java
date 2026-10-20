package org.miezmerker.backend.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class CollectorSyncEventRetention {
    private final CollectorSyncEventService events;
    public CollectorSyncEventRetention(CollectorSyncEventService events) { this.events = events; }
    @Scheduled(fixedDelayString="${miezmerker.sync-events.purge-delay-ms:3600000}")
    public void purge() { events.purge(); }
}
