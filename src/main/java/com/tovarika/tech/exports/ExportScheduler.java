package com.tovarika.tech.exports;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="tovarika.exports.worker-enabled",havingValue="true",matchIfMissing=true)
public class ExportScheduler {
    private final ExportWorker worker;
    public ExportScheduler(ExportWorker worker) {this.worker=worker;}
    @Scheduled(fixedDelayString="${tovarika.exports.poll-delay-ms:1000}")
    public void poll() {worker.runOnce();}
    @Scheduled(fixedDelayString="${tovarika.exports.cleanup-delay-ms:60000}")
    public void cleanup() {worker.cleanup();}
}
