package com.tovarika.tech.trial.domain;

import java.time.Instant;

public record TrialSession(String id, int generationLimit, int generationsUsed, Instant createdAt, Instant expiresAt) {
    public int remainingGenerations() { return generationLimit - generationsUsed; }
    public boolean exhausted() { return remainingGenerations() == 0; }
}
