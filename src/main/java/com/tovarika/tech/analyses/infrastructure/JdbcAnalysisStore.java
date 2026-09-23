package com.tovarika.tech.analyses.infrastructure;

import com.tovarika.tech.analyses.application.AnalysisStore;
import com.tovarika.tech.analyses.domain.*;
import com.tovarika.tech.shared.application.ApiFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalysisStore implements AnalysisStore {
    private final JdbcTemplate jdbc;
    public JdbcAnalysisStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockOwner(String userId, String trialId) {
        var rows = userId != null
                ? jdbc.queryForList("select id from users where id = ? and status = 'ACTIVE' for update", String.class, userId)
                : jdbc.queryForList("select id from trial_sessions where id = ? and owner_user_id is null and expires_at > current_timestamp for update", String.class, trialId);
        if (rows.isEmpty()) throw new ApiFailure(401, "AUTHENTICATION_REQUIRED", "Owner unavailable");
    }
    public Product lockProduct(String id, String userId, String trialId) {
        return jdbc.query("select id, status, analysis_job_id from products where id = ? and (owner_user_id = ? or owner_trial_session_id = ?) for update",
                (r,n) -> new Product(r.getString("id"), r.getString("status"), r.getString("analysis_job_id")), id, userId, trialId)
                .stream().findFirst().orElseThrow(() -> new ApiFailure(404, "PRODUCT_NOT_FOUND", "Product not found"));
    }
    public Optional<AnalysisJob> previous(String key, String userId, String trialId) {
        return jdbc.query("""
                select j.* from analysis_jobs j join products p on p.id=j.product_id
                where j.idempotency_key=? and (p.owner_user_id=? or p.owner_trial_session_id=?) order by j.created_at
                """, (r,n)->job(r), key, userId, trialId).stream().findFirst();
    }
    public void enqueue(AnalysisJob j, String scope, String key) {
        jdbc.update("""
                insert into analysis_jobs(id,product_id,analysis_id,owner_scope,idempotency_key,status,created_at)
                values (?,?,?,?,?,'queued',?)
                """, j.id(),j.productId(),j.analysisId(),scope,key,Timestamp.from(j.createdAt()));
        jdbc.update("update products set status='analysis_pending',analysis_job_id=?,updated_at=? where id=?",
                j.id(),Timestamp.from(j.createdAt()),j.productId());
    }
    public Optional<AnalysisJob> ownedJob(String id, String userId, String trialId) {
        return jdbc.query("""
                select j.* from analysis_jobs j join products p on p.id=j.product_id
                where j.id=? and (p.owner_user_id=? or p.owner_trial_session_id=?)
                """, (r,n)->job(r),id,userId,trialId).stream().findFirst();
    }
    public Optional<AnalysisJob> claim(Instant now, Instant leaseUntil) {
        return jdbc.query("""
                update analysis_jobs set status='processing',attempt=attempt+1,lease_until=?,started_at=coalesce(started_at,?)
                where id=(select id from analysis_jobs where status='queued' or (status='processing' and lease_until <= ?)
                    order by created_at for update skip locked limit 1) returning *
                """,(r,n)->job(r),Timestamp.from(leaseUntil),Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public Source source(String id) {
        return jdbc.queryForObject("select a.storage_key,a.media_type from assets a join products p on p.source_asset_id=a.id where p.id=?",
                (r,n)->new Source(r.getString("storage_key"),r.getString("media_type")),id);
    }
    private boolean lockCurrent(AnalysisJob j) {
        var current=jdbc.queryForList("select id from products where id=? and analysis_job_id=? for update", String.class,j.productId(),j.id());
        if (current.isEmpty()) return false;
        return !jdbc.queryForList("select id from analysis_jobs where id=? and status='processing' and attempt=? for update", String.class,j.id(),j.attempt()).isEmpty();
    }
    public boolean complete(AnalysisJob j, AnalysisResult result, String prompt, Instant now) {
        if (!lockCurrent(j)) return false;
        jdbc.update("""
                insert into product_analyses(id,product_id,title,description,idea,generation_prompt,revision,created_at,updated_at)
                values (?,?,?,?,?,?,1,?,?)
                on conflict(product_id) do update set id=excluded.id,title=excluded.title,description=excluded.description,
                    idea=excluded.idea,generation_prompt=excluded.generation_prompt,revision=1,
                    created_at=excluded.created_at,updated_at=excluded.updated_at
                """,j.analysisId(),j.productId(),result.title(),result.description(),result.idea(),prompt,Timestamp.from(now),Timestamp.from(now));
        terminal(j,"completed","analysis_ready",now);
        return true;
    }
    public boolean fail(AnalysisJob j, Instant now) {
        if (!lockCurrent(j)) return false;
        terminal(j,"failed","analysis_failed",now);
        return true;
    }
    private void terminal(AnalysisJob j,String state,String productState,Instant now) {
        jdbc.update("update analysis_jobs set status=?,finished_at=?,lease_until=null where id=?",state,Timestamp.from(now),j.id());
        jdbc.update("update products set status=?,updated_at=? where id=?",productState,Timestamp.from(now),j.productId());
    }
    private AnalysisJob job(ResultSet r) throws SQLException {
        return new AnalysisJob(r.getString("id"),r.getString("product_id"),r.getString("analysis_id"),r.getString("status"),
                r.getInt("attempt"),r.getTimestamp("created_at").toInstant(),instant(r,"started_at"),instant(r,"finished_at"));
    }
    private Instant instant(ResultSet r,String field) throws SQLException { var value=r.getTimestamp(field);return value==null?null:value.toInstant(); }
}
