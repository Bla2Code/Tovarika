package com.tovarika.tech.cards;

import com.tovarika.tech.billing.application.BillingService;
import com.tovarika.tech.billing.application.BillingStore;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.api.publicapi.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

/** Locks finance before domain. AI and object storage are never called here. */
@Service
public class CardJobBilling {
    private final JdbcTemplate jdbc;
    private final com.tovarika.tech.billing.application.BillingActivity activity;
    private final BillingService billing;
    private final BillingStore store;
    private final com.tovarika.tech.auth.application.port.AuthenticationStore auth;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean launchesEnabled;
    public CardJobBilling(JdbcTemplate jdbc, BillingService billing, BillingStore store, ObjectMapper mapper, com.tovarika.tech.auth.application.port.AuthenticationStore auth,
            @Value("${tovarika.billing.ai-admission-enabled:false}") boolean enabled,
            @Value("${tovarika.billing.ai-launches-enabled:true}") boolean launchesEnabled,
            com.tovarika.tech.billing.application.BillingActivity activity) {
        this.jdbc=jdbc; this.billing=billing; this.store=store; this.mapper=mapper; this.auth=auth; this.enabled=enabled;this.launchesEnabled=launchesEnabled;this.activity=activity;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void prepare(String userId,String trialId,String subject) {
        if (!enabled) return;
        if(userId!=null) auth.initializeBilling(userId); // Attach legacy converted trial streams before topology/wallet locks.
        var owner=owner(userId,trialId);
        store.lockWallet(owner);
        if(subject!=null) {
            jdbc.update("insert into billing_entitlements(code,subject) values('free_entire',?) on conflict do nothing",subject);
            jdbc.queryForList("select subject from billing_entitlements where code='free_entire' and subject=? for update",subject);
        }
    }
    private Owner owner(String user,String trial) { return new Owner(user==null?"trial":"user",user==null?trial:user); }
    public String digest(Object... fields) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(Arrays.asList(fields)).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String admit(String user,String trial,String key,String digest,Code code,String subject,String jobId) {
        if(!launchesEnabled && code!=Code.cpu_only && code!=Code.product_analysis)
            throw new com.tovarika.tech.shared.application.ApiFailure(503,"BILLING_AI_PAUSED","AI operations are temporarily paused");
        if(!enabled) return null;
        var identity=owner(user,trial);
        billing.grantTrial(identity,identity.kind()+":"+identity.id());
        var command=new Command("card-image-command",key,1,Map.of(1,digest),code,subject,"project_jobs",jobId);
        var admission=billing.admit(owner(user,trial),command);
        if(admission.replay()) throw new IllegalStateException("Domain receipt missing for financial replay");
        return admission.operation().snapshot().operationId();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(String jobId,String operationId) {
        if(operationId!=null) jdbc.update("update project_jobs set billing_operation_id=? where id=?",operationId,jobId);
    }
    public void verifyReplay(String jobId,String digest) {
        String stored=jdbc.queryForObject("select command_digest from project_jobs where id=?",String.class,jobId);
        // Grandfathered generation receipts had no digest; never manufacture a financial operation.
        if(stored!=null && !stored.equals(digest)) throw new com.tovarika.tech.shared.application.ApiFailure(
                409,"IDEMPOTENCY_CONFLICT","Key belongs to another command or payload");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(String jobId) {
        String operation=jdbc.queryForObject("select billing_operation_id from project_jobs where id=?",String.class,jobId);
        if(operation!=null) {
            var op=store.operation(operation);
            store.lockWallet(op.walletId());
            if(op.subject()!=null) jdbc.queryForList(
                    "select subject from billing_entitlements where code='free_entire' and subject=? for update",op.subject());
            jdbc.queryForList("select id from billing_operations where id=? for update",operation);
        }
        jdbc.queryForList("""
                select p.id from products p join projects j on j.product_id=p.id
                join project_jobs q on q.project_id=j.id where q.id=? for update of p,j
                """,jobId);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<String> claim(boolean generation,Instant now,Instant until) {
        // Candidate read has no locks. Recheck after finance/project locks; another worker may have won.
        String types=generation?"'card_generation'":"'card_image_edit','card_region_edit'";
        var ids=jdbc.queryForList("select id from project_jobs where type in ("+types+") "
                +"and (status='queued' or (status='processing' and lease_until<=?)) order by created_at,id limit 1",
                String.class,Timestamp.from(now));
        for(String id:ids) {
            lock(id);
            int changed=jdbc.update("""
                    update project_jobs set status='processing',attempt=attempt+1,lease_until=?,
                        started_at=coalesce(started_at,?),updated_at=?
                    where id=? and (status='queued' or (status='processing' and lease_until<=?))
                    """,Timestamp.from(until),Timestamp.from(now),Timestamp.from(now),id,Timestamp.from(now));
            if(changed==0) return Optional.empty(); // Do not acquire another wallet in this transaction.
            var row=jdbc.queryForMap("select billing_operation_id,billing_fence from project_jobs where id=?",id);
            String op=(String)row.get("billing_operation_id");
            if(op!=null) {
                long fence=billing.claim(op,((Number)row.get("billing_fence")).longValue());
                jdbc.update("update project_jobs set billing_fence=? where id=?",fence,id);
            }
            return Optional.of(id);
        }
        return Optional.empty();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void finish(String jobId,boolean success,boolean aiCalled) {
        var row=jdbc.queryForMap("select billing_operation_id,billing_fence from project_jobs where id=?",jobId);
        String op=(String)row.get("billing_operation_id");
        if(op!=null && !billing.finish(op,((Number)row.get("billing_fence")).longValue(),
                success && aiCalled?Outcome.captured:Outcome.released))
            throw new IllegalStateException("Financial fence disagrees with current domain attempt");
        if(success) activity.projectJob(jobId,aiCalled);
    }
    @Transactional(readOnly=true)
    public BillingOperationSnapshotDto snapshot(String jobId) {
        String id=jdbc.queryForObject("select billing_operation_id from project_jobs where id=?",String.class,jobId);
        if(id==null) return null;
        var s=store.operation(id).snapshot();
        return new BillingOperationSnapshotDto(s.operationId(),BillingOperationCodeDto.fromValue(s.operationCode().name()),
                s.priceVersion(),s.amountUnits(),BillingFundingDto.fromValue(s.funding().name()));
    }
}
