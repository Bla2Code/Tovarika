package com.tovarika.tech.exports;

import com.tovarika.tech.cards.CardGenerationStore;
import com.tovarika.tech.shared.application.ApiFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import static com.tovarika.tech.exports.ExportSnapshot.*;

@Repository
public class ExportStore {
    private final JdbcTemplate jdbc;
    private final CardGenerationStore cards;
    private final ObjectMapper mapper;
    public ExportStore(JdbcTemplate jdbc, CardGenerationStore cards, ObjectMapper mapper) {
        this.jdbc=jdbc; this.cards=cards; this.mapper=mapper;
    }
    public void lockOwnerProject(String projectId, String userId) {
        cards.lockOwner(userId, null);
        cards.lockProject(projectId, userId, null);
    }
    public Optional<Export> replay(String userId, String key, String digest) {
        if (!jdbc.queryForList("select command_digest from card_image_history_receipts where owner_scope=? and idempotency_key=?",
                String.class,userId,key).isEmpty()) throw conflict();
        var jobs=jdbc.query("select id,type,command_digest from project_jobs where owner_scope=? and idempotency_key=?",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},userId,key);
        if(jobs.isEmpty()) return Optional.empty();
        if(!"export".equals(jobs.getFirst()[1]) || !digest.equals(jobs.getFirst()[2])) throw conflict();
        return jdbc.query(select()+" where e.job_id=? and p.owner_user_id=?",this::mapExport,jobs.getFirst()[0],userId).stream().findFirst();
    }
    private ApiFailure conflict() { return new ApiFailure(409,"IDEMPOTENCY_CONFLICT","Key belongs to another command or payload"); }
    public void requireCapacity(String userId) {
        if(jdbc.queryForObject("select count(*) from project_jobs where owner_scope=? and type='export' and status in ('queued','processing')",
                Integer.class,userId)>=2) throw new ApiFailure(429,"RATE_LIMITED","Too many exports in progress");
    }
    public ExportSnapshot snapshot(String projectId, String cardId, String format) {
        var values=jdbc.query("""
                select c.position,c.current_version_id,c.image_revision,c.status,a.*
                from cards c join card_image_versions v on v.id=c.current_version_id and v.card_id=c.id
                join assets a on a.id=v.image_asset_id and a.id=c.image_asset_id
                where c.project_id=? and c.id=? for update of c
                """,(r,n)-> {
                    if(!"ready".equals(r.getString("status")) || !"card_image".equals(r.getString("purpose"))
                            || r.getString("storage_key")==null || r.getLong("image_revision")<1)
                        throw new ApiFailure(409,"RESULT_NOT_READY","A current ready card image is required");
                    String type=r.getString("media_type");
                    String ext="original".equals(format) ? extension(type) : format;
                    return new ExportSnapshot(cardId,r.getString("current_version_id"),r.getLong("image_revision"),
                            r.getString("id"),type,r.getInt("size_bytes"),"card-%02d.%s".formatted(r.getInt("position"),ext),
                            r.getString("storage_key"),(Integer)r.getObject("width"),(Integer)r.getObject("height"),
                            (Boolean)r.getObject("has_alpha"),r.getTimestamp("created_at").toInstant());
                },projectId,cardId);
        if(!values.isEmpty()) return values.getFirst();
        if(jdbc.queryForList("select id from cards where project_id=? and id=?",String.class,projectId,cardId).isEmpty())
            throw new ApiFailure(404,"CARD_NOT_FOUND","Card not found");
        throw new ApiFailure(409,"RESULT_NOT_READY","A current ready card image is required");
    }
    public static String extension(String type) {
        return switch(type) { case "image/png"->"png"; case "image/jpeg"->"jpg"; case "image/webp"->"webp";
            default->throw new ApiFailure(422,"VALIDATION_ERROR","Unsupported card image format"); };
    }
    public void enqueue(Export value, String userId, String key, String digest) {
        jdbc.update("""
                insert into project_jobs(id,project_id,type,status,owner_scope,idempotency_key,command_digest,created_at,updated_at)
                values(?,?,'export','queued',?,?,?,?,?)
                """,value.jobId(),value.projectId(),userId,key,digest,Timestamp.from(value.createdAt()),Timestamp.from(value.createdAt()));
        jdbc.update("""
                insert into card_exports(id,project_id,job_id,format,packaging,file_name,snapshot,created_at,expires_at)
                values(?,?,?,?,?,?,cast(? as jsonb),?,?)
                """,value.id(),value.projectId(),value.jobId(),value.format(),value.packaging(),value.fileName(),
                mapper.writeValueAsString(value.items()),Timestamp.from(value.createdAt()),Timestamp.from(value.expiresAt()));
    }
    public Optional<Export> owned(String id,String userId) {
        return jdbc.query(select()+" where e.id=? and p.owner_user_id=?",this::mapExport,id,userId).stream().findFirst();
    }
    public Optional<Export> find(String id) {
        return jdbc.query(select()+" where e.id=?",this::mapExport,id).stream().findFirst();
    }
    private String select() { return """
            select e.*,q.status,q.failure_code from card_exports e join project_jobs q on q.id=e.job_id
            join projects j on j.id=e.project_id join products p on p.id=j.product_id
            """; }
    public Optional<Job> ownedJob(String id,String userId) {
        return jdbc.query(jobSelect()+" where q.id=? and p.owner_user_id=?",this::mapJob,id,userId).stream().findFirst();
    }
    private String jobSelect() { return """
            select q.*,e.id export_id from project_jobs q join card_exports e on e.job_id=q.id
            join projects j on j.id=q.project_id join products p on p.id=j.product_id
            """; }
    public Optional<Job> claim(Instant now,Instant leaseUntil) {
        var ids=jdbc.queryForList("""
                update project_jobs set status='processing',attempt=attempt+1,lease_until=?,started_at=coalesce(started_at,?),updated_at=?
                where id=(select id from project_jobs where type='export' and
                    (status='queued' or (status='processing' and lease_until<=?))
                    order by created_at for update skip locked limit 1) returning id
                """,String.class,Timestamp.from(leaseUntil),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        return ids.isEmpty()?Optional.empty():jdbc.query(jobSelect()+" where q.id=?",this::mapJob,ids.getFirst()).stream().findFirst();
    }
    public void reserveFile(String key,String exportId,String assetId,Instant now) {
        jdbc.update("insert into export_files(storage_key,export_id,asset_id,created_at) values(?,?,?,?)",
                key,exportId,assetId,Timestamp.from(now));
    }
    public boolean lockReservedFile(String key) {
        return !jdbc.queryForList("select storage_key from export_files where storage_key=? for update",
                String.class,key).isEmpty();
    }
    public boolean complete(Job job, Artifact artifact, boolean createdFile, Instant now) {
        // Same project lock as deletion and image operations, then the attempt lock.
        if(jdbc.queryForList("select id from projects where id=? for update",String.class,job.projectId()).isEmpty()) return false;
        if(!lockCurrent(job)) return false;
        var value=find(job.exportId()).orElse(null);
        if(value==null || !value.expiresAt().isAfter(now)) return false;
        if(createdFile) jdbc.update("""
                insert into assets(id,purpose,media_type,size_bytes,width,height,has_alpha,url,storage_key,created_at)
                values(?,'export_file',?,?,?,?,?,'',?,?)
                """,artifact.id(),artifact.mediaType(),artifact.sizeBytes(),artifact.width(),artifact.height(),artifact.hasAlpha(),
                artifact.storageKey(),Timestamp.from(now));
        jdbc.update("update card_exports set artifact_asset_id=? where id=?",artifact.id(),value.id());
        jdbc.update("update project_jobs set status='completed',finished_at=?,lease_until=null,updated_at=? where id=?",
                Timestamp.from(now),Timestamp.from(now),job.id());
        return true;
    }
    public boolean fail(Job job,Instant now) {
        if(jdbc.queryForList("select id from projects where id=? for update",String.class,job.projectId()).isEmpty() || !lockCurrent(job)) return false;
        jdbc.update("update project_jobs set status='failed',failure_code='EXPORT_FAILED',finished_at=?,lease_until=null,updated_at=? where id=?",
                Timestamp.from(now),Timestamp.from(now),job.id());
        return true;
    }
    private boolean lockCurrent(Job job) {
        return !jdbc.queryForList("select id from project_jobs where id=? and status='processing' and attempt=? for update",
                String.class,job.id(),job.attempt()).isEmpty();
    }
    public Artifact artifact(String id) {
        return jdbc.query("select * from assets where id=? and storage_key is not null",(r,n)->new Artifact(
                r.getString("id"),r.getString("purpose"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("storage_key"),
                (Integer)r.getObject("width"),(Integer)r.getObject("height"),(Boolean)r.getObject("has_alpha"),r.getTimestamp("created_at").toInstant()),id)
                .stream().findFirst().orElseThrow(()->new ApiFailure(404,"ASSET_NOT_FOUND","Export file not found"));
    }
    public List<String> cleanupKeys(Instant now) {
        return jdbc.queryForList(cleanupSelect()+" order by f.created_at limit 100",String.class,Timestamp.from(now));
    }
    public boolean lockCleanupFile(String key,Instant now) {
        // An uploader holds only its receipt row; cleanup skips it without blocking project operations.
        return !jdbc.queryForList(cleanupSelect()+" and f.storage_key=? for update of f skip locked",
                String.class,Timestamp.from(now),key).isEmpty();
    }
    private String cleanupSelect() {
        return """
                select f.storage_key from export_files f left join card_exports e on e.id=f.export_id
                left join project_jobs q on q.id=e.job_id
                where (e.id is null or (e.expires_at<=? and q.status in ('completed','failed')) or
                    (q.status in ('completed','failed') and e.artifact_asset_id is distinct from f.asset_id))
                """;
    }
    public void forgetFile(String key) {
        jdbc.update("update card_exports set artifact_asset_id=null where expires_at<=current_timestamp and artifact_asset_id in (select asset_id from export_files where storage_key=?)",key);
        jdbc.update("delete from assets where purpose='export_file' and storage_key=? and not exists(select 1 from card_exports where artifact_asset_id=assets.id)",key);
        jdbc.update("delete from export_files where storage_key=?",key);
    }
    private Export mapExport(ResultSet r,int row) throws SQLException {
        var items=List.of(mapper.readValue(r.getString("snapshot"),ExportSnapshot[].class));
        return new Export(r.getString("id"),r.getString("project_id"),r.getString("job_id"),r.getString("format"),
                r.getString("packaging"),r.getString("file_name"),items,r.getString("artifact_asset_id"),r.getString("status"),
                r.getString("failure_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant());
    }
    private Job mapJob(ResultSet r,int row) throws SQLException {
        return new Job(r.getString("id"),r.getString("export_id"),r.getString("project_id"),r.getString("status"),
                r.getString("failure_code"),r.getInt("attempt"),r.getTimestamp("created_at").toInstant(),
                instant(r,"started_at"),instant(r,"finished_at"));
    }
    private Instant instant(ResultSet r,String name) throws SQLException { var value=r.getTimestamp(name);return value==null?null:value.toInstant(); }
}
