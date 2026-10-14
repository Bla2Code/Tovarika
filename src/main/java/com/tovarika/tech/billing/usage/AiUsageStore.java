package com.tovarika.tech.billing.usage;

public interface AiUsageStore {
    AiCallContext.Identity identity(String namespace,String jobId,int attempt);
    void save(AiCallContext.Identity identity,AiCallContext.Call call,String jobOutcome);
}
