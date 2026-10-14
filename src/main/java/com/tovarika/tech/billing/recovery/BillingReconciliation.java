package com.tovarika.tech.billing.recovery;

import com.tovarika.tech.billing.application.*;
import com.tovarika.tech.billing.domain.Billing.Outcome;
import com.tovarika.tech.cards.CardJobBilling;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Only missing or terminal jobs are released. Processing/queued reserves never expire by TTL. */
@Service
public class BillingReconciliation {
    private final JdbcTemplate jdbc;
    private final BillingStore store;
    private final BillingService billing;
    private final CardJobBilling jobs;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final java.util.concurrent.atomic.AtomicLong overdue = new java.util.concurrent.atomic.AtomicLong();
    public BillingReconciliation(JdbcTemplate jdbc,BillingStore store,BillingService billing,CardJobBilling jobs,
            TransactionTemplate tx,MeterRegistry metrics) {
        this.jdbc=jdbc;this.store=store;this.billing=billing;this.jobs=jobs;this.tx=tx;this.metrics=metrics;
        metrics.gauge("tovarika.billing.reserves.overdue",overdue);
    }
    @Scheduled(fixedDelayString="${tovarika.billing.reconciliation-delay-ms:60000}")
    public void reconcile() {
        var ids=jdbc.queryForList("""
                select o.id from billing_operations o left join project_jobs q on q.id=o.job_id
                where o.state='reserved' and o.job_namespace='project_jobs'
                  and (q.id is null or q.status in ('completed','failed')) order by o.created_at,o.id limit 100
                """,String.class);
        for(var id:ids) {
            try { if(Boolean.TRUE.equals(tx.execute(t -> release(id)))) metrics.counter("tovarika.billing.reconciliation.released").increment(); }
            catch(RuntimeException failure) { metrics.counter("tovarika.billing.reconciliation.failures").increment(); }
        }
        overdue.set(jdbc.queryForObject("""
                select count(*) from billing_operations where state='reserved' and created_at<clock_timestamp()-interval '24 hours'
                """,Long.class));
    }
    public boolean release(String id) {
        var op=store.operation(id);
        store.lockWallet(op.walletId());
        if(op.subject()!=null) jdbc.queryForList("select subject from billing_entitlements where code='free_entire' and subject=? for update",op.subject());
        jdbc.queryForList("select id from billing_operations where id=? for update",id);
        op=store.operation(id);
        if(!op.state().equals("reserved") || !op.jobNamespace().equals("project_jobs")) return false;
        // Match the normal wallet -> entitlement -> operation -> project -> job order.
        var exists=jdbc.queryForList("select id from project_jobs where id=?",String.class,op.jobId());
        if(!exists.isEmpty()) jobs.lock(op.jobId());
        var states=jdbc.queryForList("select status from project_jobs where id=? for update",String.class,op.jobId());
        if(!states.isEmpty() && !states.getFirst().equals("completed") && !states.getFirst().equals("failed")) return false;
        // Completed + reserved is an invariant violation: conservatively release, never invent a debit.
        if(!states.isEmpty() && states.getFirst().equals("completed")) metrics.counter("tovarika.billing.reconciliation.terminal_anomaly").increment();
        return billing.finish(id,op.fence(),Outcome.released);
    }
}
