package com.tovarika.tech.analyses.application;

import com.tovarika.tech.billing.usage.AiUsageService;

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
    private final AiUsageService usage;
    private final com.tovarika.tech.billing.recovery.AiJobRecovery recovery;
    private final MeterRegistry metrics;
    public AnalysisWorker(AnalysisStore store,AnalysisProvider provider,ProductStorage storage,GenerationPromptBuilder prompts,
            TransactionTemplate transactions,Clock clock,MeterRegistry metrics,AiUsageService usage, com.tovarika.tech.billing.recovery.AiJobRecovery recovery) {
        this.store=store;this.provider=provider;this.storage=storage;this.prompts=prompts;this.transactions=transactions;this.clock=clock;this.metrics=metrics;this.usage=usage;this.recovery=recovery;
    }
    public boolean runOnce() {
        var claimed=transactions.execute(tx -> store.claim(clock.instant(),clock.instant().plus(com.tovarika.tech.billing.recovery.AiJobRecovery.LEASE)));
        if (claimed==null || claimed.isEmpty()) return false;
        var job=claimed.get();
        var context=usage.context("analysis_jobs",job.id(),job.attempt());
        Timer.Sample sample=Timer.start(metrics);
        String outcome="unknown";
        try (var lease=recovery.start("analysis_jobs",job.id(),job.attempt())) {
            if (job.attempt()>3) throw new IllegalStateException("Recovery attempts exhausted");
            var source=store.source(job.productId());
            var result=provider.analyze(job.id(),storage.read(source.storageKey()),source.mediaType(),context);
            var prompt=prompts.build(result);
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.complete(job,result,prompt,clock.instant())));
            outcome=applied?"completed":"stale";
        } catch (RuntimeException failure) {
            // All provider/storage messages may contain secrets. Persist only the stable public failure category.
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.fail(job,clock.instant())));
            outcome=applied?"failed":"stale";
        } finally {
            usage.finish(context,outcome);
            sample.stop(metrics.timer("tovarika.analysis.latency","outcome",outcome));
            metrics.counter("tovarika.analysis.operations","outcome",outcome).increment();
        }
        return true;
    }
}
