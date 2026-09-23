package com.tovarika.tech.products.application;

import com.tovarika.tech.products.domain.ProductView;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface ProductStore {
    void reserve(String key, Instant now);
    void lockReservation(String key);
    void create(ProductView product, String userId, String trialId);
    void release(String key);
    boolean hasAsset(String key);
    List<String> abandoned(Instant before);
    Optional<ProductView> findOwned(String id, String userId, String trialId);
    Optional<ProductView.Asset> findAsset(String id);
}
