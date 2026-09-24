package com.tovarika.tech.products.api;

import com.tovarika.api.publicapi.ProductsApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.products.application.*;
import com.tovarika.tech.products.domain.ProductView;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.shared.application.ApiFailure;
import java.io.IOException;
import java.net.URI;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductsController implements ProductsApi {
    private final ProductService products;
    private final WorkspaceIdentityResolver identities;
    private final RequestAuthenticationContext request;
    private final AssetLinks links;
    public ProductsController(ProductService products, WorkspaceIdentityResolver identities,
            RequestAuthenticationContext request, AssetLinks links) {
        this.products=products; this.identities=identities; this.request=request; this.links=links;
    }
    public ResponseEntity<ProductDto> createProduct(MultipartFile image, String name) {
        var owner = identities.resolve();
        if (image.getSize() > ImageValidator.MAX_BYTES) throw new ApiFailure(413, "FILE_TOO_LARGE", "Image exceeds 10 MiB");
        try (var input = image.getInputStream()) {
            var product = products.create(owner.userId(), owner.trialSessionId(), request.rateLimitSubject("products"),
                    input.readNBytes(ImageValidator.MAX_BYTES + 1), image.getOriginalFilename(), name);
            return ResponseEntity.status(201).header("Cache-Control", "no-store").body(dto(product));
        } catch (IOException failure) { throw new ApiFailure(400, "UPLOAD_VALIDATION_ERROR", "Cannot read upload"); }
    }
    public ResponseEntity<ProductDto> getProduct(String id) {
        var owner = identities.resolve();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(dto(products.get(id, owner.userId(), owner.trialSessionId())));
    }
    private ProductDto dto(ProductView p) {
        var a=p.asset(); var link=links.create(a.id());
        var asset = new AssetDto(a.id(), AssetPurposeDto.SOURCE_IMAGE, a.mediaType(), a.size(), URI.create(link.url()),
                a.createdAt().atOffset(ZoneOffset.UTC)).width(a.width()).height(a.height()).expiresAt(link.expiresAt().atOffset(ZoneOffset.UTC));
        return new ProductDto(p.id(), p.name(), asset, ProductStatusDto.fromValue(p.status()),
                p.createdAt().atOffset(ZoneOffset.UTC), p.updatedAt().atOffset(ZoneOffset.UTC)).analysisJobId(p.analysisJobId());
    }
}
