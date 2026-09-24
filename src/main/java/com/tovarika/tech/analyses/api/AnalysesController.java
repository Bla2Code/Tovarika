package com.tovarika.tech.analyses.api;

import com.tovarika.api.publicapi.AnalysesApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.analyses.application.AnalysisService;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import com.tovarika.tech.analyses.domain.ProductAnalysisView;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysesController implements AnalysesApi {
    private final AnalysisService service;
    private final WorkspaceIdentityResolver identities;
    public AnalysesController(AnalysisService service,WorkspaceIdentityResolver identities) { this.service=service;this.identities=identities; }
    public ResponseEntity<AsyncOperationDto> analyzeProduct(String key,String productId) {
        var owner=identities.resolve();
        var job=service.start(productId,key,owner.userId(),owner.trialSessionId());
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(new AsyncOperationDto(job.id(),"queued",
                new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.PRODUCT_ANALYSIS,job.analysisId()),1000));
    }
    public ResponseEntity<ProductAnalysisDto> getProductAnalysis(String productId) {
        var owner=identities.resolve();
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(dto(service.getAnalysis(productId,owner.userId(),owner.trialSessionId())));
    }
    public ResponseEntity<ProductAnalysisDto> updateProductAnalysis(String productId,UpdateProductAnalysisRequestDto request) {
        var owner=identities.resolve();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(dto(service.updateAnalysis(
                productId,owner.userId(),owner.trialSessionId(),request.getTitle(),request.getDescription(),request.getIdea())));
    }
    private ProductAnalysisDto dto(ProductAnalysisView analysis) {
        return new ProductAnalysisDto(analysis.id(),analysis.productId(),analysis.description(),analysis.idea(),
                analysis.generationPrompt(),analysis.revision(),analysis.createdAt().atOffset(ZoneOffset.UTC),
                analysis.updatedAt().atOffset(ZoneOffset.UTC)).title(analysis.title());
    }
}
