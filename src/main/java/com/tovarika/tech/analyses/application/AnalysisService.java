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
    private final Clock clock;
    public AnalysisService(AnalysisStore store,AuthenticationRateLimiter limiter,Clock clock) {
        this.store=store;this.limiter=limiter;this.clock=clock;
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
    private String id(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
}
