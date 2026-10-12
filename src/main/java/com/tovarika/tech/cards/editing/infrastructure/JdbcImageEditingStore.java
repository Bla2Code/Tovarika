package com.tovarika.tech.cards.editing.infrastructure;

import com.tovarika.tech.cards.CardGenerationStore;
import com.tovarika.tech.cards.editing.application.ImageEditingStore;
import com.tovarika.tech.cards.editing.domain.ImageEditJob;
import com.tovarika.tech.shared.application.ApiFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcImageEditingStore implements ImageEditingStore {
    private final JdbcTemplate jdbc;
    private final CardGenerationStore cards;
    public JdbcImageEditingStore(JdbcTemplate jdbc, CardGenerationStore cards) { this.jdbc=jdbc; this.cards=cards; }
    public void lockOwnerProject(String projectId, String userId, String trialId) {
        cards.lockOwner(userId,trialId);
        cards.lockProject(projectId,userId,trialId);
    }
    public Current current(String projectId, String cardId) {
        return jdbc.query("""
                select c.current_version_id,c.image_revision,c.status,v.previous_version_id,
                    (select version_id from card_image_redo_stack where card_id=c.id order by position desc limit 1)
                from cards c left join card_image_versions v on v.id=c.current_version_id
                where c.id=? and c.project_id=?
                """, (r,n)->new Current(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5)),cardId,projectId)
                .stream().findFirst().orElseThrow(()->new ApiFailure(404,"CARD_NOT_FOUND","Card not found"));
    }
    public void requireIdle(String projectId) {
        if(cards.busy(projectId)) throw new ApiFailure(409,"CARD_BUSY","Project is processing an operation");
    }
    public Optional<String> replay(String scope,String key,String digest,boolean history) {
        var receipt=jdbc.queryForList("select command_digest from card_image_history_receipts where owner_scope=? and idempotency_key=?",String.class,scope,key);
        if(!receipt.isEmpty()) {
            if(!history || !receipt.getFirst().equals(digest)) throw conflict();
            return Optional.of("history");
        }
        var jobs=jdbc.query("select id,command_digest from project_jobs where owner_scope=? and idempotency_key=?",
                (r,n)->new String[]{r.getString(1),r.getString(2)},scope,key);
        if(jobs.isEmpty()) return Optional.empty();
        if(history || !digest.equals(jobs.getFirst()[1])) throw conflict();
        return Optional.of(jobs.getFirst()[0]);
    }
    private ApiFailure conflict() { return new ApiFailure(409,"IDEMPOTENCY_CONFLICT","Key belongs to another command or payload"); }
    public void enqueue(String id,String projectId,String cardId,String type,String scope,String key,String digest,
            String base,long revision,String payload,Instant now) {
        jdbc.update("""
                insert into project_jobs(id,project_id,card_id,type,status,owner_scope,idempotency_key,
                    command_digest,base_version_id,expected_image_revision,image_edit_payload,created_at,updated_at)
                values(?,?,?,?,'queued',?,?,?,?,?,cast(? as jsonb),?,?)
                """,id,projectId,cardId,type,scope,key,digest,base,revision,payload,Timestamp.from(now),Timestamp.from(now));
        jdbc.update("update cards set status='regenerating',last_image_job_id=?,error_code=null,updated_at=? where id=?",id,Timestamp.from(now),cardId);
    }
    public void undo(String projectId,String cardId,String scope,String key,String digest,Current current,Instant now) {
        int changed=jdbc.update("""
                update cards c set current_version_id=v.id,image_asset_id=v.image_asset_id,aspect_ratio=v.aspect_ratio,
                    image_revision=c.image_revision+1,status='ready',error_code=null,updated_at=?
                from card_image_versions v where c.id=? and v.card_id=c.id and v.id=?
                    and c.current_version_id=? and c.image_revision=?
                """,Timestamp.from(now),cardId,current.previousVersionId(),current.versionId(),current.revision());
        if(changed!=1) throw new ApiFailure(409,"CARD_VERSION_CONFLICT","Current image changed");
        jdbc.update("""
                insert into card_image_redo_stack(card_id,position,version_id)
                select ?,coalesce(max(position),0)+1,? from card_image_redo_stack where card_id=?
                """,cardId,current.versionId(),cardId);
        recordHistory(projectId,cardId,scope,key,digest,now);
    }
    public void redo(String projectId,String cardId,String scope,String key,String digest,Current current,Instant now) {
        int changed=jdbc.update("""
                update cards c set current_version_id=v.id,image_asset_id=v.image_asset_id,aspect_ratio=v.aspect_ratio,
                    image_revision=c.image_revision+1,status='ready',error_code=null,updated_at=?
                from card_image_versions v where c.id=? and v.card_id=c.id and v.id=?
                    and v.previous_version_id=c.current_version_id
                    and c.current_version_id=? and c.image_revision=?
                """,Timestamp.from(now),cardId,current.redoVersionId(),current.versionId(),current.revision());
        if(changed!=1) throw new ApiFailure(409,"CARD_VERSION_CONFLICT","Current image changed");
        jdbc.update("delete from card_image_redo_stack where card_id=? and version_id=?",cardId,current.redoVersionId());
        recordHistory(projectId,cardId,scope,key,digest,now);
    }
    private void recordHistory(String projectId,String cardId,String scope,String key,String digest,Instant now) {
        jdbc.update("""
                insert into card_image_history_receipts(owner_scope,idempotency_key,project_id,card_id,command_digest,created_at)
                values(?,?,?,?,?,?)
                """,scope,key,projectId,cardId,digest,Timestamp.from(now));
    }
    public Optional<ImageEditJob> findJob(String id,String userId,String trialId) {
        return jdbc.query("""
                select q.* from project_jobs q join projects j on j.id=q.project_id join products p on p.id=j.product_id
                where q.id=? and q.type in ('card_image_edit','card_region_edit')
                    and (p.owner_user_id=? or p.owner_trial_session_id=?)
                """,this::map,id,userId,trialId).stream().findFirst();
    }
    public Optional<ImageEditJob> claim(Instant now) {
        return jdbc.query("""
                update project_jobs set status='processing',attempt=attempt+1,lease_until=?,
                    started_at=coalesce(started_at,?),updated_at=?
                where id=(select id from project_jobs where type in ('card_image_edit','card_region_edit')
                    and (status='queued' or (status='processing' and lease_until<=?))
                    order by created_at for update skip locked limit 1) returning *
                """,this::map,Timestamp.from(now.plusSeconds(300)),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now))
                .stream().findFirst();
    }
    public Source source(ImageEditJob job) {
        return jdbc.queryForObject("""
                select a.storage_key,a.media_type,v.aspect_ratio from card_image_versions v
                join assets a on a.id=v.image_asset_id where v.id=? and v.card_id=?
                """,(r,n)->new Source(r.getString(1),r.getString(2),r.getString(3)),job.baseVersionId(),job.cardId());
    }
    private boolean lockJob(ImageEditJob job) {
        return !jdbc.queryForList("select id from project_jobs where id=? and status='processing' and attempt=? for update",
                String.class,job.id(),job.attempt()).isEmpty();
    }
    public boolean complete(ImageEditJob job,Output output,Instant now) {
        if(!lockJob(job)) return false;
        var current=jdbc.queryForList("""
                select id from cards where id=? and current_version_id=? and image_revision=?
                    and last_image_job_id=? for update
                """,String.class,job.cardId(),job.baseVersionId(),job.expectedRevision(),job.id());
        if(current.isEmpty()) { failLocked(job,"CARD_VERSION_CONFLICT",now); return false; }
        String versionId="ver_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("""
                insert into assets(id,purpose,media_type,size_bytes,width,height,has_alpha,url,storage_key,created_at)
                values(?,'card_image','image/png',?,?,?,?, '',?,?)
                """,output.id(),output.size(),output.width(),output.height(),output.hasAlpha(),output.key(),Timestamp.from(now));
        // The result ratio belongs to this operation; never mutate the original version.
        String ratio=jdbc.queryForObject("""
                select coalesce(image_edit_payload->>'aspectRatio',v.aspect_ratio)
                from project_jobs q join card_image_versions v on v.id=q.base_version_id where q.id=?
                """,String.class,job.id());
        jdbc.update("""
                insert into card_image_versions(id,card_id,previous_version_id,image_asset_id,aspect_ratio,created_at)
                values(?,?,?,?,?,?)
                """,versionId,job.cardId(),job.baseVersionId(),output.id(),ratio,Timestamp.from(now));
        // Branching clears navigation only; all immutable versions and Assets remain stored.
        jdbc.update("delete from card_image_redo_stack where card_id=?",job.cardId());
        long revision=job.expectedRevision()+1;
        jdbc.update("""
                update cards set current_version_id=?,image_asset_id=?,image_revision=?,aspect_ratio=?,
                    status='ready',error_code=null,updated_at=? where id=?
                """,versionId,output.id(),revision,ratio,Timestamp.from(now),job.cardId());
        jdbc.update("""
                update project_jobs set status='completed',result_version_id=?,result_asset_id=?,result_image_revision=?,
                    finished_at=?,updated_at=?,lease_until=null where id=?
                """,versionId,output.id(),revision,Timestamp.from(now),Timestamp.from(now),job.id());
        return true;
    }
    public boolean fail(ImageEditJob job,String code,Instant now) {
        if(!lockJob(job)) return false;
        failLocked(job,code,now); return true;
    }
    private void failLocked(ImageEditJob job,String code,Instant now) {
        jdbc.update("update cards set status='ready',error_code=null,updated_at=? where id=? and last_image_job_id=?",
                Timestamp.from(now),job.cardId(),job.id());
        jdbc.update("update project_jobs set status='failed',failure_code=?,finished_at=?,updated_at=?,lease_until=null where id=?",
                code,Timestamp.from(now),Timestamp.from(now),job.id());
    }
    private ImageEditJob map(ResultSet r,int row) throws SQLException {
        return new ImageEditJob(r.getString("id"),r.getString("project_id"),r.getString("card_id"),r.getString("type"),
                r.getString("status"),r.getString("failure_code"),r.getInt("attempt"),r.getString("base_version_id"),
                r.getLong("expected_image_revision"),r.getString("image_edit_payload"),r.getString("result_version_id"),
                r.getString("result_asset_id"),(Long)r.getObject("result_image_revision"),instant(r,"created_at"),
                instant(r,"started_at"),instant(r,"finished_at"));
    }
    private Instant instant(ResultSet r,String key) throws SQLException { var t=r.getTimestamp(key);return t==null?null:t.toInstant(); }
}
