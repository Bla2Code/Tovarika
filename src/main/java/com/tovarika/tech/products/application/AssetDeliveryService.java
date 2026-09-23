package com.tovarika.tech.products.application;

import com.tovarika.tech.shared.application.ApiFailure;
import org.springframework.stereotype.Service;

@Service
public class AssetDeliveryService {
    private final AssetLinks links;
    private final ProductStore store;
    private final ProductStorage storage;
    public AssetDeliveryService(AssetLinks links, ProductStore store, ProductStorage storage) {
        this.links=links; this.store=store; this.storage=storage;
    }
    public Content download(String id, long expires, String signature) {
        links.verify(id, expires, signature);
        var asset=store.findAsset(id).orElseThrow(() -> new ApiFailure(404, "ASSET_NOT_FOUND", "Asset not found"));
        return new Content(asset.mediaType(), storage.read(asset.storageKey()));
    }
    public record Content(String mediaType, byte[] bytes) {}
}
