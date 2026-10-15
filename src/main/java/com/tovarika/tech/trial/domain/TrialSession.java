package com.tovarika.tech.trial.domain;

import com.tovarika.tech.billing.domain.Billing.Wallet;
import java.time.Instant;

/** Legacy generation counters are compatibility snapshots, never billing admission limits. */
public record TrialSession(String id, int generationLimit, int generationsUsed, Instant createdAt, Instant expiresAt,
                           Long allowanceUnits, Wallet wallet) {
    public TrialSession(String id, int generationLimit, int generationsUsed, Instant createdAt, Instant expiresAt) {
        this(id, generationLimit, generationsUsed, createdAt, expiresAt, null, null);
    }
    public int remainingGenerations() { return generationLimit - generationsUsed; }
    public boolean exhausted() { return wallet != null && wallet.totalUnits() == 0; }
}
