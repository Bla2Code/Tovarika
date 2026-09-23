package com.tovarika.tech.products.api;

import com.tovarika.tech.products.application.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Binary delivery transport; public API metadata exposes only expiring capability URLs. */
@RestController
public class AssetDownloadController {
    private final AssetDeliveryService delivery;
    public AssetDownloadController(AssetDeliveryService delivery) { this.delivery=delivery; }
    @GetMapping("/media/assets/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id, @RequestParam long expires, @RequestParam String signature) {
        var content=delivery.download(id, expires, signature);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType()))
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline").body(content.bytes());
    }
}
