package com.tovarika.tech.analyses.application;

import com.tovarika.tech.analyses.domain.AnalysisJob;
import com.tovarika.tech.shared.application.ApiFailure;
import com.tovarika.tech.auth.application.AuthenticationProperties.RateLimit.Rule;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisService {
    private final AnalysisStore store;
    private final AuthenticationRateLimiter limiter;
    private final GenerationPromptBuilder prompts;
    private final Clock clock;
    public AnalysisService(AnalysisStore store,AuthenticationRateLimiter limiter,
            GenerationPromptBuilder prompts,Clock clock) {
        this.store=store;this.limiter=limiter;this.prompts=prompts;this.clock=clock;
    }
    @Transactional
    public AnalysisJob start(String productId,String key,String userId,String trialId) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw new ApiFailure(400,"VALIDATION_ERROR","Invalid idempotency key");
        store.lockOwner(userId,trialId);
        var product=store.lockProduct(productId,userId,trialId);
        var previous=store.previous(key,userId,trialId);
        if (previous.isPresent()) {
            if (!previous.get().productId().equals(productId)) throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT","Key belongs to another operation");
            return previous.get();
        }
        if (product.status().equals("analysis_pending")) throw new ApiFailure(409,"ANALYSIS_ALREADY_RUNNING","Analysis is already running");
        String scope=userId==null?trialId:userId;
        limiter.check(AuthenticationRateLimiter.Scope.PRODUCT_ANALYSIS,scope,new Rule(30,Duration.ofHours(1)));
        var job=new AnalysisJob(id("job_"),productId,id("anl_"),"queued",0,clock.instant(),null,null);
        store.enqueue(job,scope,key);
        return job;
    }
    @Transactional(readOnly=true)
    public AnalysisJob getJob(String id,String userId,String trialId) {
        return store.ownedJob(id,userId,trialId).orElseThrow(()->new ApiFailure(404,"JOB_NOT_FOUND","Job not found"));
    }
    @Transactional
    public com.tovarika.tech.analyses.domain.ProductAnalysisView getAnalysis(
            String productId,String userId,String trialId) {
        var product=store.lockProduct(productId,userId,trialId);
        requireReady(product);
        return store.findAnalysis(productId)
                .orElseThrow(()->new ApiFailure(404,"ANALYSIS_NOT_FOUND","Analysis not found"));
    }
    @Transactional
    public com.tovarika.tech.analyses.domain.ProductAnalysisView updateAnalysis(
            String productId,String userId,String trialId,String title,String description,String idea) {
        store.lockOwner(userId,trialId);
        var product=store.lockProduct(productId,userId,trialId);
        requireReady(product);
        var current=store.findAnalysis(productId)
                .orElseThrow(()->new ApiFailure(404,"ANALYSIS_NOT_FOUND","Analysis not found"));
        String nextTitle=title==null?current.title():title;
        String nextDescription=description==null?current.description():description;
        String nextIdea=idea==null?current.idea():idea;
        var content=new com.tovarika.tech.analyses.domain.AnalysisResult(nextTitle,nextDescription,nextIdea);
        Instant updatedAt=clock.instant();
        if(!updatedAt.isAfter(current.updatedAt())) updatedAt=current.updatedAt().plusNanos(1_000);
        return store.updateAnalysis(productId,nextTitle,nextDescription,nextIdea,prompts.build(content),updatedAt);
    }
    private void requireReady(AnalysisStore.Product product) {
        switch(product.status()) {
            case "analysis_ready" -> { }
            case "analysis_pending" -> throw new ApiFailure(409,"RESULT_NOT_READY","Analysis is still processing");
            case "analysis_failed" -> throw new ApiFailure(409,"ANALYSIS_FAILED","Product analysis failed");
            default -> throw new ApiFailure(404,"ANALYSIS_NOT_FOUND","Analysis not found");
        }
    }
    private String id(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
}
