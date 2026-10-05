package com.tovarika.tech.cards;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CardGenerationStore {
    private final JdbcTemplate jdbc;

    public CardGenerationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockOwner(String userId, String trialId) {
        List<String> rows = userId != null
                ? jdbc.queryForList("select id from users where id=? and status='ACTIVE' for update", String.class, userId)
                : jdbc.queryForList("""
                        select id from trial_sessions where id=? and owner_user_id is null
                        and expires_at > current_timestamp for update
                        """, String.class, trialId);
        if (rows.isEmpty()) throw new com.tovarika.tech.shared.application.ApiFailure(
                401, "AUTHENTICATION_REQUIRED", "Owner is unavailable");
    }

    public ProjectGenerationContext lockProject(String projectId, String userId, String trialId) {
        return jdbc.query("""
                select j.id,j.product_id,j.card_count,p.status product_status,
                       a.description,a.revision,s.storage_key source_storage_key,s.media_type source_media_type
                from projects j join products p on p.id=j.product_id
                left join product_analyses a on a.product_id=p.id
                join assets s on s.id=p.source_asset_id
                where j.id=? and (p.owner_user_id=? or p.owner_trial_session_id=?)
                for update of j,p
                """, (result, row) -> new ProjectGenerationContext(
                        result.getString("id"), result.getString("product_id"), result.getInt("card_count"),
                        result.getString("product_status"), result.getString("description"),
                        (Integer) result.getObject("revision"), result.getString("source_storage_key"),
                        result.getString("source_media_type")), projectId, userId, trialId)
                .stream().findFirst().orElseThrow(() -> new com.tovarika.tech.shared.application.ApiFailure(
                        404, "PROJECT_NOT_FOUND", "Project not found"));
    }

    public Optional<CardGenerationJob> previous(String ownerScope, String key) {
        return jdbc.query("""
                select * from project_jobs where owner_scope=? and idempotency_key=?
                order by created_at limit 1
                """, this::mapJob, ownerScope, key).stream().findFirst();
    }

    public void requireOwnedProject(String projectId, String userId, String trialId) {
        if (jdbc.queryForList("""
                select j.id from projects j join products p on p.id=j.product_id
                where j.id=? and (p.owner_user_id=? or p.owner_trial_session_id=?)
                """, String.class, projectId, userId, trialId).isEmpty()) {
            throw new com.tovarika.tech.shared.application.ApiFailure(404, "PROJECT_NOT_FOUND", "Project not found");
        }
    }

    public void enqueue(CardGenerationJob job, String templateId, String referenceAssetId, String ratio, String prompt,
            String recipeJson, int analysisRevision, String ownerScope, String idempotencyKey, Instant now) {
        jdbc.update("""
                insert into cards(id,project_id,position,status,aspect_ratio,template_id,prompt,idea,
                                  recipe_snapshot,reference_asset_snapshot_id,analysis_revision,created_at,updated_at)
                values(?, ?, 1, 'generating', ?, ?, ?, null, cast(? as jsonb), ?, ?, ?, ?)
                """, job.cardId(), job.projectId(), ratio, templateId, prompt, recipeJson, referenceAssetId,
                analysisRevision, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into project_jobs(id,project_id,type,status,created_at,updated_at,card_id,
                                         owner_scope,idempotency_key,attempt)
                values(?,?,'card_generation','queued',?,?,?,?,?,0)
                """, job.id(), job.projectId(), Timestamp.from(now), Timestamp.from(now), job.cardId(),
                ownerScope, idempotencyKey);
        jdbc.update("""
                update projects set card_count=1, selected_template_id=coalesce(?,selected_template_id), updated_at=?
                where id=?
                """, templateId, Timestamp.from(now), job.projectId());
    }

    public Optional<CardGenerationJob> ownedJob(String id, String userId, String trialId) {
        return jdbc.query("""
                select q.* from project_jobs q join projects j on j.id=q.project_id
                join products p on p.id=j.product_id
                where q.id=? and q.type='card_generation'
                  and (p.owner_user_id=? or p.owner_trial_session_id=?)
                """, this::mapJob, id, userId, trialId).stream().findFirst();
    }

    public Optional<CardGenerationJob> claim(Instant now, Instant leaseUntil) {
        return jdbc.query("""
                update project_jobs set status='processing',attempt=attempt+1,lease_until=?,
                    started_at=coalesce(started_at,?),updated_at=?
                where id=(select id from project_jobs
                    where type='card_generation' and (status='queued' or (status='processing' and lease_until<=?))
                    order by created_at for update skip locked limit 1)
                returning *
                """, this::mapJob, Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now)).stream().findFirst();
    }

    public GenerationSource source(CardGenerationJob job) {
        return jdbc.queryForObject("""
                select c.prompt,c.aspect_ratio,c.template_id,
                       source.storage_key source_storage_key,source.media_type source_media_type,
                       reference.storage_key reference_storage_key,reference.media_type reference_media_type
                from cards c join projects j on j.id=c.project_id join products p on p.id=j.product_id
                join assets source on source.id=p.source_asset_id
                left join assets reference on reference.id=c.reference_asset_snapshot_id
                where c.id=? and c.project_id=?
                """, (result, row) -> new GenerationSource(
                        result.getString("prompt"), result.getString("aspect_ratio"),
                        result.getString("template_id"), result.getString("source_storage_key"),
                        result.getString("source_media_type"), result.getString("reference_storage_key"),
                        result.getString("reference_media_type")), job.cardId(), job.projectId());
    }

    public boolean complete(CardGenerationJob job, GeneratedAsset asset, Instant now) {
        if (!lockCurrent(job)) return false;
        jdbc.update("""
                insert into assets(id,purpose,media_type,size_bytes,width,height,url,storage_key,created_at)
                values(?,'card_image',?,?,?,?, '',?,?)
                """, asset.id(), asset.mediaType(), asset.sizeBytes(), asset.width(), asset.height(),
                asset.storageKey(), Timestamp.from(now));
        jdbc.update("update cards set status='ready',image_asset_id=?,updated_at=?,error_code=null where id=?",
                asset.id(), Timestamp.from(now), job.cardId());
        jdbc.update("""
                update project_jobs set status='completed',finished_at=?,lease_until=null,updated_at=? where id=?
                """, Timestamp.from(now), Timestamp.from(now), job.id());
        jdbc.update("update projects set preview_asset_id=coalesce(preview_asset_id,?),updated_at=? where id=?",
                asset.id(), Timestamp.from(now), job.projectId());
        return true;
    }

    public boolean fail(CardGenerationJob job, Instant now) {
        if (!lockCurrent(job)) return false;
        jdbc.update("update cards set status='error',error_code='GENERATION_FAILED',updated_at=? where id=?",
                Timestamp.from(now), job.cardId());
        jdbc.update("""
                update project_jobs set status='failed',failure_code='GENERATION_FAILED',finished_at=?,
                    lease_until=null,updated_at=? where id=?
                """, Timestamp.from(now), Timestamp.from(now), job.id());
        return true;
    }

    public Optional<CardView> ownedCard(String projectId, String cardId, String userId, String trialId) {
        return jdbc.query(cardSelect() + """
                where c.project_id=? and c.id=? and (p.owner_user_id=? or p.owner_trial_session_id=?)
                """, this::mapCard, projectId, cardId, userId, trialId).stream().findFirst();
    }

    public List<CardView> ownedCards(String projectId, String userId, String trialId) {
        return jdbc.query(cardSelect() + """
                where c.project_id=? and (p.owner_user_id=? or p.owner_trial_session_id=?) order by c.position
                """, this::mapCard, projectId, userId, trialId);
    }

    private String cardSelect() {
        return """
                select c.*,a.id image_id,a.media_type image_media_type,a.size_bytes image_size_bytes,
                       a.width image_width,a.height image_height,a.created_at image_created_at
                from cards c join projects j on j.id=c.project_id join products p on p.id=j.product_id
                left join assets a on a.id=c.image_asset_id
                """;
    }

    private boolean lockCurrent(CardGenerationJob job) {
        return !jdbc.queryForList("""
                select id from project_jobs where id=? and status='processing' and attempt=? for update
                """, String.class, job.id(), job.attempt()).isEmpty();
    }

    private CardGenerationJob mapJob(ResultSet result, int row) throws SQLException {
        return new CardGenerationJob(
                result.getString("id"), result.getString("project_id"), result.getString("card_id"),
                result.getString("status"), result.getString("failure_code"), result.getInt("attempt"),
                result.getTimestamp("created_at").toInstant(), instant(result, "started_at"),
                instant(result, "finished_at"));
    }

    private CardView mapCard(ResultSet result, int row) throws SQLException {
        String assetId = result.getString("image_id");
        CardAssetView image = assetId == null ? null : new CardAssetView(
                assetId, result.getString("image_media_type"), result.getInt("image_size_bytes"),
                (Integer) result.getObject("image_width"), (Integer) result.getObject("image_height"),
                result.getTimestamp("image_created_at").toInstant());
        return new CardView(
                result.getString("id"), result.getString("project_id"), result.getInt("position"),
                result.getString("status"), result.getString("aspect_ratio"), result.getString("template_id"),
                result.getString("error_code"), image, result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private Instant instant(ResultSet result, String field) throws SQLException {
        Timestamp value = result.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public record ProjectGenerationContext(
            String projectId, String productId, int cardCount, String productStatus,
            String description, Integer analysisRevision, String sourceStorageKey, String sourceMediaType) {}

    public record GenerationSource(
            String prompt, String aspectRatio, String templateId, String sourceStorageKey,
            String sourceMediaType, String referenceStorageKey, String referenceMediaType) {}

    public record GeneratedAsset(
            String id, String storageKey, String mediaType, int sizeBytes, int width, int height) {}
}
