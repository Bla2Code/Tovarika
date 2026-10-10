package com.tovarika.tech.analyses.api;

import com.tovarika.api.publicapi.JobsApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.analyses.application.AnalysisService;
import com.tovarika.tech.cards.CardGenerationService;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobsController implements JobsApi {
    private final AnalysisService service;
    private final CardGenerationService cards;
    private final WorkspaceIdentityResolver identities;
    private final com.tovarika.tech.cards.editing.application.CardImageEditingService edits;
    private final com.tovarika.tech.exports.ExportService exports;
    public JobsController(AnalysisService service,CardGenerationService cards,WorkspaceIdentityResolver identities,
            com.tovarika.tech.cards.editing.application.CardImageEditingService edits,
            com.tovarika.tech.exports.ExportService exports) {
        this.service=service;this.cards=cards;this.identities=identities;this.edits=edits;
        this.exports=exports;
    }
    public ResponseEntity<JobDto> getJob(String id) {
        var owner=identities.resolve();
        var exportJob=exports.findJob(id,owner.userId());
        if(exportJob.isPresent()) {
            var job=exportJob.get();
            var dto=new JobDto(job.id(),JobTypeDto.EXPORT,JobStatusDto.fromValue(job.status()),
                    new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.EXPORT,job.exportId()),1000,job.createdAt().atOffset(ZoneOffset.UTC));
            if(job.startedAt()!=null) dto.startedAt(job.startedAt().atOffset(ZoneOffset.UTC));
            if(job.finishedAt()!=null) dto.finishedAt(job.finishedAt().atOffset(ZoneOffset.UTC));
            if("failed".equals(job.status())) dto.failure(new JobFailureDto(ErrorCodeDto.EXPORT_FAILED,"Export failed","req_"+job.id()));
            return ResponseEntity.ok().header("Cache-Control","no-store").body(dto);
        }
        var editJob=edits.findJob(id,owner.userId(),owner.trialSessionId());
        if(editJob.isPresent()) {
            var job=editJob.get();
            var dto=new JobDto(job.id(),JobTypeDto.fromValue(job.type()),JobStatusDto.fromValue(job.status()),
                    new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.CARD,job.cardId()),1000,
                    job.createdAt().atOffset(ZoneOffset.UTC));
            dto.baseVersionId(job.baseVersionId());
            dto.progress(switch(job.status()) {case "queued"->0;case "processing"->10;case "completed"->100;default->null;});
            if(job.startedAt()!=null) dto.startedAt(job.startedAt().atOffset(ZoneOffset.UTC));
            if(job.finishedAt()!=null) dto.finishedAt(job.finishedAt().atOffset(ZoneOffset.UTC));
            if(job.resultVersionId()!=null) dto.result(new ImageJobResultDto(job.resultVersionId(),job.resultAssetId(),job.resultRevision()));
            if("failed".equals(job.status())) dto.failure(new JobFailureDto(ErrorCodeDto.fromValue(job.failureCode()),
                    "Image edit failed","req_"+job.id()));
            return ResponseEntity.ok().header("Cache-Control","no-store").body(dto);
        }
        var cardJob=cards.findJob(id,owner.userId(),owner.trialSessionId());
        if (cardJob.isPresent()) {
            var job=cardJob.get();
            var dto=new JobDto(job.id(),JobTypeDto.CARD_GENERATION,JobStatusDto.fromValue(job.status()),
                    new ResourceReferenceDto(ResourceReferenceDto.TypeEnum.CARD,job.cardId()),1000,
                    job.createdAt().atOffset(ZoneOffset.UTC));
            dto.progress(switch(job.status()) { case "queued" -> 0; case "processing" -> 10; case "completed" -> 100; default -> null; });
            if (job.startedAt()!=null) dto.startedAt(job.startedAt().atOffset(ZoneOffset.UTC));
            if (job.finishedAt()!=null) dto.finishedAt(job.finishedAt().atOffset(ZoneOffset.UTC));
            if (job.status().equals("failed")) dto.failure(new JobFailureDto(
                    ErrorCodeDto.GENERATION_FAILED,"Card generation failed","req_"+job.id()));
            return ResponseEntity.ok().header("Cache-Control","no-store").body(dto);
        }
        var job=service.findJob(id,owner.userId(),owner.trialSessionId())
                .orElseThrow(()->new com.tovarika.tech.shared.application.ApiFailure(404,"JOB_NOT_FOUND","Job not found"));
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
