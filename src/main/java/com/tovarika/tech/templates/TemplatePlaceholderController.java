package com.tovarika.tech.templates;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplatePlaceholderController {
    @GetMapping(value = "/media/template-placeholder.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> placeholder() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(TemplatePlaceholder.png());
    }
}
