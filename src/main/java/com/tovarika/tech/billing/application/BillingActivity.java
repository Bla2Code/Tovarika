package com.tovarika.tech.billing.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable success receipts inside the domain transaction. No wallet locks after domain locks. */
@Service
public class BillingActivity {
    private final JdbcTemplate jdbc;
    public BillingActivity(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void projectJob(String id,boolean aiCalled) {
        jdbc.update("""
            insert into billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,billing_operation_id,occurred_at)
            select 'project_jobs:'||q.id,case when p.owner_user_id is null then 'trial' else 'user' end,
                coalesce(p.owner_user_id,p.owner_trial_session_id),
                case when q.type='card_generation' then coalesce(o.operation_code,'image_generation')
                    when q.type='card_region_edit' then 'image_region_edit'
                    when q.image_edit_payload->>'kind'='resize' then case when ? then 'image_outpaint' else 'cpu_only' end
                    when q.image_edit_payload->>'kind'='erase' then 'image_erase'
                    when q.image_edit_payload->>'kind'='remove_background' then 'image_remove_background'
                    when q.image_edit_payload->>'kind'='region' then 'image_region_edit'
                    else 'image_entire_edit' end,
                q.billing_operation_id,q.finished_at
            from project_jobs q join projects j on j.id=q.project_id join products p on p.id=j.product_id
            left join billing_operations o on o.id=q.billing_operation_id
            where q.id=? and q.status='completed'
            on conflict(reference) do nothing
            """,aiCalled,id);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void analysisJob(String id) {
        jdbc.update("""
            insert into billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,occurred_at)
            select 'analysis_jobs:'||q.id,case when p.owner_user_id is null then 'trial' else 'user' end,
                coalesce(p.owner_user_id,p.owner_trial_session_id),'product_analysis',q.finished_at
            from analysis_jobs q join products p on p.id=q.product_id where q.id=? and q.status='completed'
            on conflict(reference) do nothing
            """,id);
    }
}
