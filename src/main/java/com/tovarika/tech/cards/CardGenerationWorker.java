package com.tovarika.tech.cards;

import com.tovarika.tech.billing.usage.AiUsageService;

import com.tovarika.tech.images.application.GenerationRequest;
import com.tovarika.tech.images.application.ImageGenerationService;
import com.tovarika.tech.products.application.ProductStorage;
import com.tovarika.tech.templates.TemplatePlaceholder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CardGenerationWorker {
    private final CardGenerationStore store;
    private final ProductStorage storage;
    private final ImageGenerationService images;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AiUsageService usage;
    private final com.tovarika.tech.billing.recovery.AiJobRecovery recovery;
    private final MeterRegistry metrics;

    public CardGenerationWorker(CardGenerationStore store, ProductStorage storage,
            ImageGenerationService images, TransactionTemplate transactions, Clock clock, MeterRegistry metrics,AiUsageService usage, com.tovarika.tech.billing.recovery.AiJobRecovery recovery) {
        this.store = store;
        this.storage = storage;
        this.images = images;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;this.usage=usage;this.recovery=recovery;
    }

    public boolean runOnce() {
        var claimed = transactions.execute(tx -> store.claim(clock.instant(), clock.instant().plus(com.tovarika.tech.billing.recovery.AiJobRecovery.LEASE)));
        if (claimed == null || claimed.isEmpty()) return false;
        var job = claimed.get();
        var context=usage.context("project_jobs",job.id(),job.attempt());
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "unknown";
        String generatedKey = null;
        try (var lease=recovery.start("project_jobs",job.id(),job.attempt())) {
            recovery.check(job.attempt(),job.createdAt());
            var source = store.source(job);
            var inputImages = new ArrayList<GenerationRequest.InputImage>();
            inputImages.add(new GenerationRequest.InputImage(
                    storage.read(source.sourceStorageKey()), source.sourceMediaType(), "product"));
            if (source.templateId() != null) {
                byte[] reference = source.referenceStorageKey() == null
                        ? TemplatePlaceholder.png() : storage.read(source.referenceStorageKey());
                String mediaType = source.referenceMediaType() == null ? "image/png" : source.referenceMediaType();
                inputImages.add(new GenerationRequest.InputImage(reference, mediaType, "template_reference"));
            }
            int[] size = dimensions(source.aspectRatio());
            var generated = images.generate("chatgpt",
                    new GenerationRequest(source.prompt(), inputImages, size[0], size[1]),context);
            String assetId = id("asset_");
            generatedKey = "cards/" + assetId;
            storage.put(generatedKey, generated.bytes(), generated.mediaType());
            var asset = new CardGenerationStore.GeneratedAsset(assetId, generatedKey, generated.mediaType(),
                    generated.bytes().length, generated.width(), generated.height());
            boolean applied = Boolean.TRUE.equals(
                    transactions.execute(tx -> store.complete(job, asset, clock.instant())));
            if (!applied) recovery.deleteUnreferenced(storage,generatedKey);
            outcome = applied ? "completed" : "stale";
        } catch (RuntimeException failure) {
            // Resolves a possibly committed terminal transition under the job lock before cleanup.
            boolean applied = Boolean.TRUE.equals(transactions.execute(tx -> store.fail(job, clock.instant())));
            if (generatedKey != null) recovery.deleteUnreferenced(storage,generatedKey);
            outcome = applied ? "failed" : "stale";
        } finally {
            usage.finish(context,outcome);
            sample.stop(metrics.timer("tovarika.card.generation.latency", "outcome", outcome));
            metrics.counter("tovarika.card.generation.operations", "outcome", outcome).increment();
        }
        return true;
    }

    private int[] dimensions(String ratio) {
        return switch (ratio) {
            case "1:1" -> new int[] {1024, 1024};
            case "3:4" -> new int[] {1152, 1536};
            case "4:5" -> new int[] {1024, 1280};
            case "16:9" -> new int[] {1536, 864};
            default -> throw new IllegalArgumentException("Unsupported ratio");
        };
    }

    private String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
