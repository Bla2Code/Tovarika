package com.tovarika.tech.billing.usage;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcAiUsageStore implements AiUsageStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcAiUsageStore(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    @Override
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public AiCallContext.Identity identity(String namespace,String id,int attempt) {
        if("analysis_jobs".equals(namespace)) return new AiCallContext.Identity(namespace,id,attempt,null,"free_operation");
        if(!"project_jobs".equals(namespace)) throw new IllegalArgumentException("Unknown job namespace");
        var rows=jdbc.queryForList("""
                select j.billing_operation_id,o.funding from project_jobs j
                left join billing_operations o on o.id=j.billing_operation_id where j.id=?
                """,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("Unknown job");
        var row=rows.getFirst();
        return new AiCallContext.Identity(namespace,id,attempt,(String)row.get("billing_operation_id"),
                row.get("funding")==null?"legacy":(String)row.get("funding"));
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void save(AiCallContext.Identity identity,AiCallContext.Call call,String jobOutcome) {
        var u=call.usage;
        jobOutcome=jobOutcome(identity,jobOutcome);
        int inserted=jdbc.update("""
                insert into billing_ai_attempts(id,job_namespace,job_id,job_attempt,operation_id,funding,provider,is_stub,
                    provider_outcome,job_outcome,http_status,requested_main_model,requested_image_model,
                    requested_size,requested_quality,started_at,finished_at,response_id,actual_main_model,
                    provider_created_at,provider_completed_at,usage_schema_version)
                values(?,?,?,?,?,?,'openai',?,?,?,?,?,?,?,?,?,?,?,?,?,?,1) on conflict(id) do nothing
                """,call.id,identity.namespace(),identity.jobId(),identity.attempt(),identity.operationId(),identity.funding(),
                call.stub,call.outcome,jobOutcome,call.httpStatus,call.requestedMainModel,call.requestedImageModel,call.requestedSize,
                call.requestedQuality,Timestamp.from(call.startedAt),call.finishedAt==null?null:Timestamp.from(call.finishedAt),
                u.responseId(),u.actualMainModel(),u.createdAt(),u.completedAt());
        if(inserted==0) return; // Transactional idempotency also covers an ambiguous commit acknowledgement.
        int index=0;
        for(var component:u.components()) {
            var estimate=ProviderCost.estimate(component,rate(component,call),call.stub);
            jdbc.update("""
                    insert into billing_ai_usage(attempt_id,component_index,scope,provider_item_id,actual_model,
                        actual_size,actual_quality,provider_status,counters,classification,cost_state,
                        estimated_amount,currency,rate_version)
                    values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                    """,call.id,index++,component.scope(),component.id(),component.actualModel(),component.size(),component.quality(),
                    component.status(),mapper.writeValueAsString(component.counters()),component.classification(),estimate.state(),
                    estimate.amount(),estimate.currency(),estimate.version());
        }
        if(index==0) jdbc.update("""
                insert into billing_ai_usage(attempt_id,component_index,scope,counters,classification,cost_state)
                values(?,0,'unknown','{}'::jsonb,'missing_usage',?)
                """,call.id,call.stub?"stub":"unknown");
    }
    private String jobOutcome(AiCallContext.Identity identity,String fallback) {
        if(!List.of("project_jobs","analysis_jobs").contains(identity.namespace())) return fallback;
        var rows=jdbc.queryForList("select status,attempt from "+identity.namespace()+" where id=?",identity.jobId());
        if(rows.isEmpty()) return fallback; // Deleted workspace does not invalidate technical history.
        var row=rows.getFirst();
        if(((Number)row.get("attempt")).intValue()!=identity.attempt()) return "stale";
        String status=(String)row.get("status");
        return List.of("completed","failed").contains(status)?status:fallback;
    }
    private ProviderCost.Rate rate(ResponsesUsage.Component component,AiCallContext.Call call) {
        if(call.stub || !"response".equals(component.scope()) || component.actualModel()==null) return null;
        var rows=jdbc.queryForList("""
                select r.version,r.currency,r.category,r.amount_per_million,r.effective_from from billing_provider_rates r
                where r.provider='openai' and r.model=? and r.scope='response' and r.version=(
                    select version from billing_provider_rates where provider='openai' and model=? and scope='response'
                    and effective_from<=? order by effective_from desc,version desc limit 1)
                """,component.actualModel(),component.actualModel(),Timestamp.from(call.startedAt));
        if(rows.isEmpty()) return null;
        var rates=new HashMap<String,BigDecimal>(); String currency=(String)rows.getFirst().get("currency");
        for(var row:rows) {
            if(!currency.equals(row.get("currency")) || !rows.getFirst().get("effective_from").equals(row.get("effective_from"))) return null;
            rates.put((String)row.get("category"),(BigDecimal)row.get("amount_per_million"));
        }
        return new ProviderCost.Rate((String)rows.getFirst().get("version"),currency,Map.copyOf(rates));
    }
}
