package com.tovarika.tech.trial.application;

import com.tovarika.tech.trial.domain.TrialSession;
import java.time.Instant;
import java.util.Optional;

public interface TrialSessionStore {
    void create(TrialSession session, String tokenHash);
    Optional<TrialSession> findAvailable(String tokenHash, Instant now);
}
