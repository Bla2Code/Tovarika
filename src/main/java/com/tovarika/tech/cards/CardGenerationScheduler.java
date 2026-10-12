package com.tovarika.tech.cards;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tovarika.cards.worker-enabled", havingValue = "true", matchIfMissing = true)
public class CardGenerationScheduler {
    private final CardGenerationWorker worker;
    private final com.tovarika.tech.cards.editing.application.CardImageEditingWorker edits;

    public CardGenerationScheduler(CardGenerationWorker worker,
            com.tovarika.tech.cards.editing.application.CardImageEditingWorker edits) {
        this.worker = worker;
        this.edits = edits;
    }

    @Scheduled(fixedDelayString = "${tovarika.cards.poll-delay-ms:1000}")
    public void poll() {
        worker.runOnce();
        edits.runOnce();
    }
}
