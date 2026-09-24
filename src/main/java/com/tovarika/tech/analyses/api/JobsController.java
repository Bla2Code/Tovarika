package com.tovarika.tech.analyses.api;

import com.tovarika.api.publicapi.JobsApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.analyses.application.AnalysisService;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobsController implements JobsApi {
    private final AnalysisService service;
    private final WorkspaceIdentityResolver identities;
    public JobsController(AnalysisService service,WorkspaceIdentityResolver identities) { this.service=service;this.identities=identities; }
    public ResponseEntity<JobDto> getJob(String id) {
        var owner=identities.resolve();
        var job=service.getJob(id,owner.userId(),owner.trialSessionId());
        var dto=new JobDto(job.id(),JobTypeDto.PRODUCT_ANALYSIS,JobStatusDto.fromValue(job.status()),
                new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.PRODUCT_ANALYSIS,job.analysisId()),1000,
                job.createdAt().atOffset(ZoneOffset.UTC));
        dto.progress(switch(job.status()) { case "queued" -> 0; case "processing" -> 10; case "completed" -> 100; default -> null; });
        if (job.startedAt()!=null) dto.startedAt(job.startedAt().atOffset(ZoneOffset.UTC));
        if (job.finishedAt()!=null) dto.finishedAt(job.finishedAt().atOffset(ZoneOffset.UTC));
        if (job.status().equals("failed")) dto.failure(new JobFailureDto(ErrorCodeDto.ANALYSIS_FAILED,"Product analysis failed","req_"+job.id()));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(dto);
    }
}
