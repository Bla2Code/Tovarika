package com.tovarika.tech.analyses.infrastructure;

import com.tovarika.tech.analyses.application.AnalysisWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="tovarika.analysis.worker-enabled",havingValue="true",matchIfMissing=true)
public class AnalysisScheduler {
    private final AnalysisWorker worker;
    public AnalysisScheduler(AnalysisWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelayString="${tovarika.analysis.poll-delay-ms:1000}")
    public void poll() { worker.runOnce(); }
}
