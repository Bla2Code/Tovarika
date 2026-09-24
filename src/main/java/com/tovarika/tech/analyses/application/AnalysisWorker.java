package com.tovarika.tech.analyses.application;

import com.tovarika.tech.products.application.ProductStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalysisWorker {
    private final AnalysisStore store;
    private final AnalysisProvider provider;
    private final ProductStorage storage;
    private final GenerationPromptBuilder prompts;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry metrics;
    public AnalysisWorker(AnalysisStore store,AnalysisProvider provider,ProductStorage storage,GenerationPromptBuilder prompts,
            TransactionTemplate transactions,Clock clock,MeterRegistry metrics) {
        this.store=store;this.provider=provider;this.storage=storage;this.prompts=prompts;this.transactions=transactions;this.clock=clock;this.metrics=metrics;
    }
    public boolean runOnce() {
        var claimed=transactions.execute(tx -> store.claim(clock.instant(),clock.instant().plusSeconds(300)));
        if (claimed==null || claimed.isEmpty()) return false;
        var job=claimed.get();
        Timer.Sample sample=Timer.start(metrics);
        String outcome="failed";
        try {
            if (job.attempt()>3) throw new IllegalStateException("Recovery attempts exhausted");
            var source=store.source(job.productId());
            var result=provider.analyze(job.id(),storage.read(source.storageKey()),source.mediaType());
            var prompt=prompts.build(result);
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.complete(job,result,prompt,clock.instant())));
            outcome=applied?"completed":"stale";
        } catch (RuntimeException failure) {
            // All provider/storage messages may contain secrets. Persist only the stable public failure category.
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.fail(job,clock.instant())));
            outcome=applied?"failed":"stale";
        } finally {
            sample.stop(metrics.timer("tovarika.analysis.latency","outcome",outcome));
            metrics.counter("tovarika.analysis.operations","outcome",outcome).increment();
        }
        return true;
    }
}
