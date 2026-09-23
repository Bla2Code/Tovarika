package com.tovarika.tech.analyses.api;

import com.tovarika.api.publicapi.AnalysesApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.analyses.application.AnalysisService;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import com.tovarika.tech.shared.application.ApiFailure;
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
        throw new ApiFailure(404,"ANALYSIS_NOT_FOUND","Analysis retrieval is not implemented yet");
    }
    public ResponseEntity<ProductAnalysisDto> updateProductAnalysis(String productId,UpdateProductAnalysisRequestDto request) {
        throw new ApiFailure(404,"ANALYSIS_NOT_FOUND","Analysis editing is not implemented yet");
    }
}
