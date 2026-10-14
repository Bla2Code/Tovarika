package com.tovarika.tech.billing.usage;

import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/** Bounded metadata-only retry. All failures remain outside domain/finance transactions. */
@Service
public class AiUsageService {
    private final AiUsageStore store;
    private final Clock clock;
    private final MeterRegistry metrics;
    public AiUsageService(AiUsageStore store,Clock clock,MeterRegistry metrics) {
        this.store=store;this.clock=clock;this.metrics=metrics;
    }
    public AiCallContext context(String namespace,String jobId,int attempt) {
        AiCallContext.Identity identity=new AiCallContext.Identity(namespace,jobId,attempt,null,
                "analysis_jobs".equals(namespace)?"free_operation":"unknown");
        try { identity=store.identity(namespace,jobId,attempt); }
        catch(RuntimeException ignored) { metric("identity_lost"); }
        return new AiCallContext(identity,clock);
    }
    public void finish(AiCallContext context,String outcome) {
        for(var call:context.calls()) {
            boolean saved=false;
            for(int attempt=0;attempt<3;attempt++) {
                try { store.save(context.identity,call,outcome); saved=true; break; }
                catch(RuntimeException ignored) { metric("save_failure"); }
            }
            if(!saved) metric("metadata_lost");
        }
    }
    private void metric(String category) {
        try { metrics.counter("tovarika.billing.ai.usage", "event",category).increment(); }
        catch(RuntimeException ignored) { /* Metrics must not cause a provider/domain retry either. */ }
    }
}
