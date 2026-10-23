package com.tovarika.tech.billing.payments;

import java.time.Instant;
import java.time.ZoneId;

public final class PackageLifetime {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private PackageLifetime() {}
    public static Instant expiresAt(Instant paidAt) {
        return paidAt.atZone(ZONE).plusMonths(1).toInstant();
    }
}
