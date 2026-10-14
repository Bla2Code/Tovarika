package com.tovarika.tech.billing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Financial types contain no workspace models or product names. */
public final class Billing {
    private Billing() {}
    public static final long MAX_UNITS = 9_007_199_254_740_991L;
    public static long units(long value) {
        if (value < 0 || value > MAX_UNITS) throw new IllegalArgumentException("Amount outside safe integer range");
        return value;
    }
    public static long add(long a, long b) { return units(Math.addExact(a, b)); }
    public static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }

    public enum Code {
        image_generation, image_entire_edit, image_region_edit, image_erase,
        image_remove_background, image_outpaint, product_analysis, cpu_only
    }
    public enum Funding { wallet, free_entitlement, free_operation }
    public enum Outcome { captured, released }
    public record Owner(String kind, String id) {
        public Owner {
            if (!List.of("user", "trial").contains(kind) || id == null || id.isBlank() || id.length() > 100)
                throw new IllegalArgumentException("Invalid financial owner");
        }
    }
    public record Wallet(String id, long totalUnits, long reservedUnits, long usedUnits,
                         long sequence, Instant updatedAt) {
        public Wallet { units(totalUnits); units(reservedUnits); units(usedUnits);
            if (reservedUnits > totalUnits) throw new IllegalArgumentException("Invalid reserve"); }
        public long availableUnits() { return totalUnits - reservedUnits; }
    }
    public record Catalog(String version, long trialAllowanceUnits, int freeEntireSuccessesPerCard,
                          Map<Code, Long> prices, Instant effectiveFrom) {
        public Catalog {
            prices = Map.copyOf(prices);
            units(trialAllowanceUnits);
            if (prices.size() != Code.values().length || freeEntireSuccessesPerCard != 1)
                throw new IllegalArgumentException("Incomplete billing configuration");
            for (Code code : Code.values()) {
                Long amount = prices.get(code);
                if (amount == null) throw new IllegalArgumentException("Missing price");
                units(amount);
                if ((code == Code.product_analysis || code == Code.cpu_only) != (amount == 0))
                    throw new IllegalArgumentException("Invalid free/paid price");
            }
            if (!prices.get(Code.image_generation).equals(prices.get(Code.image_entire_edit)))
                throw new IllegalArgumentException("Entire and generation must have equal prices");
        }
    }
    public record Snapshot(String operationId, Code operationCode, String priceVersion,
                           long amountUnits, Funding funding) {}
    /** Digests supplied for every supported version: replay compares the stored version first. */
    public record Command(String namespace, String key, int digestVersion, Map<Integer, String> digests,
                          Code code, String entitlementSubject, String jobNamespace, String jobId, String originWalletId) {
        public Command(String namespace, String key, int digestVersion, Map<Integer, String> digests,
                       Code code, String entitlementSubject, String jobNamespace, String jobId) {
            this(namespace, key, digestVersion, digests, code, entitlementSubject, jobNamespace, jobId, null);
        }
        public Command {
            digests = Map.copyOf(digests);
            for (String value : List.of(namespace, key, jobNamespace, jobId))
                if (value.isBlank() || value.length() > 200) throw new IllegalArgumentException("Invalid command identifier");
            if (digestVersion < 1 || !digests.containsKey(digestVersion) || code == null)
                throw new IllegalArgumentException("Invalid command digest version");
            for (String digest : digests.values())
                if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA256 digest");
            if (entitlementSubject != null && (code != Code.image_entire_edit ||
                    entitlementSubject.isBlank() || entitlementSubject.length() > 200))
                throw new IllegalArgumentException("Invalid free-entire subject");
            if (code == Code.image_entire_edit && entitlementSubject == null)
                throw new IllegalArgumentException("Entire requires stable card subject");
        }
    }
    public record Operation(Snapshot snapshot, String walletId, String namespace, String key,
                            int digestVersion, String digest, String subject,
                            String jobNamespace, String jobId, String state, long fence) {}
    public record Admission(Operation operation, boolean replay) {}
    public record Entry(String id, String kind, Code operationCode, long amountUnits,
                        long balanceAfterUnits, Instant occurredAt, long sequence) {}
    public record HistoryQuery(LocalDate from, LocalDate to, String sort, int limit, String cursor) {}
    public record History(List<Entry> items, boolean hasMore, String nextCursor) {}
}
