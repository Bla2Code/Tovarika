package com.tovarika.tech.products.application;

import com.tovarika.tech.products.domain.ProductView;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.auth.application.AuthenticationProperties.RateLimit.Rule;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ProductService {
    private final ProductStore store;
    private final ProductStorage storage;
    private final ImageValidator images;
    private final TransactionTemplate transactions;
    private final AuthenticationRateLimiter limiter;
    private final Clock clock;
    public ProductService(ProductStore store, ProductStorage storage, ImageValidator images,
            TransactionTemplate transactions, AuthenticationRateLimiter limiter, Clock clock) {
        this.store=store; this.storage=storage; this.images=images; this.transactions=transactions; this.limiter=limiter; this.clock=clock;
    }
    public ProductView create(String userId, String trialId, String subject, byte[] original, String filename, String name) {
        limiter.check(AuthenticationRateLimiter.Scope.PRODUCT_UPLOAD, subject, new Rule(30, Duration.ofHours(1)));
        var info = images.validate(original);
        String safeName = name == null ? filenameName(filename) : name.strip();
        if (safeName.isBlank() || safeName.length() > 200) throw new ApiFailure(400, "VALIDATION_ERROR", "Name must contain 1 to 200 characters");
        String key = "products/" + UUID.randomUUID();
        Instant now = clock.instant();
        var asset = new ProductView.Asset(id("asset_"), info.mediaType(), original.length, info.width(), info.height(), key, now);
        var product = new ProductView(id("prd_"), safeName, "uploaded", null, asset, now, now);
        transactions.executeWithoutResult(tx -> store.reserve(key, now));
        try {
            transactions.executeWithoutResult(tx -> {
                store.lockReservation(key);
                storage.put(key, original, info.mediaType());
                store.create(product, userId, trialId);
                store.release(key);
            });
        } catch (RuntimeException failure) {
            // A durable reservation remains if storage cleanup fails or the process crashes.
            cleanup(key);
            throw failure;
        }
        return product;
    }
    public ProductView get(String id, String userId, String trialId) {
        return store.findOwned(id, userId, trialId)
                .orElseThrow(() -> new ApiFailure(404, "PRODUCT_NOT_FOUND", "Product not found"));
    }
    private void cleanup(String key) {
        try {
            transactions.executeWithoutResult(tx -> {
                if (!store.hasAsset(key)) storage.delete(key);
                store.release(key);
            });
        } catch (RuntimeException deferred) {
            // Retain durable reservation for retry; never log provider messages or object keys.
        }
    }
    @Scheduled(fixedDelayString = "${tovarika.products.cleanup-delay-ms:60000}")
    public void cleanupAbandoned() {
        transactions.executeWithoutResult(tx -> {
            for (String key : store.abandoned(clock.instant().minus(Duration.ofHours(1)))) cleanup(key);
        });
    }
    private String filenameName(String filename) {
        String base = filename == null ? "" : filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.replaceAll("[^\\p{L}\\p{N} _-]", "").strip();
        return base.isBlank() ? "Товар" : base.substring(0, Math.min(base.length(), 200));
    }
    private String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
}
