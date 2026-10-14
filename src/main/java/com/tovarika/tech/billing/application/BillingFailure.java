package com.tovarika.tech.billing.application;

import com.tovarika.tech.shared.application.ApiFailure;
import java.util.Map;

public class BillingFailure extends ApiFailure {
    private final Map<String, Object> details;
    public BillingFailure(int status, String code, String message) { this(status, code, message, Map.of()); }
    public BillingFailure(int status, String code, String message, Map<String, Object> details) {
        super(status, code, message); this.details = Map.copyOf(details);
    }
    public Map<String, Object> details() { return details; }
    public static BillingFailure conflict() {
        return new BillingFailure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key has another command");
    }
    public static BillingFailure validation() {
        return new BillingFailure(400, "VALIDATION_ERROR", "Invalid billing request");
    }
}
