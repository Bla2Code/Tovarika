package com.tovarika.tech.exports;

import com.tovarika.tech.products.application.AssetLinks;
import com.tovarika.tech.products.application.ProductStorage;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExportDownloadController {
    private final ExportService exports;
    private final ExportStore store;
    private final AssetLinks links;
    private final ProductStorage storage;
    public ExportDownloadController(ExportService exports,ExportStore store,AssetLinks links,ProductStorage storage) {
        this.exports=exports;this.store=store;this.links=links;this.storage=storage;
    }
    @GetMapping("/media/assets/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id,@RequestParam long expires,@RequestParam String signature) {
        links.verifyExport(id,expires,signature);
        var value=exports.download(id);var asset=store.artifact(value.artifactId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.mediaType())).contentLength(asset.sizeBytes())
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition",ContentDisposition.attachment().filename(value.fileName(),StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(storage.open(asset.storageKey())));
    }
}
