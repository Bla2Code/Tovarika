package com.tovarika.tech.project;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectService {
    static final String DEFAULT_ASPECT_RATIO = "3:4";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ProjectService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    ProjectView create(ProjectOwner owner, String productId, String requestedName, String requestedAspectRatio) {
        Product product = findProduct(productId);
        if (!product.ownedBy(owner)) {
            throw ProjectException.forbidden("Product is not accessible");
        }
        String projectId = "prj_" + UUID.randomUUID().toString().replace("-", "");
        String name = requestedName == null ? product.name() : requestedName;
        String aspectRatio = requestedAspectRatio == null ? DEFAULT_ASPECT_RATIO : requestedAspectRatio;
        Instant now = Instant.now(clock);
        try {
            jdbc.update(
                    """
                    insert into projects
                        (id, product_id, name, default_aspect_ratio, card_count, created_at, updated_at)
                    values (?, ?, ?, ?, 0, ?, ?)
                    """,
                    projectId,
                    productId,
                    name,
                    aspectRatio,
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DuplicateKeyException conflict) {
            throw ProjectException.conflict();
        }
        return getOwned(projectId, owner);
    }

    @Transactional(readOnly = true)
    ProjectView getOwned(String projectId, ProjectOwner owner) {
        return jdbc.query(
                        """
                        select j.id, j.name, j.product_id, j.selected_template_id,
                               j.default_aspect_ratio, j.card_count, j.created_at, j.updated_at,
                               p.owner_user_id, p.owner_trial_session_id,
                               a.id asset_id, a.purpose asset_purpose, a.media_type asset_media_type,
                               a.size_bytes asset_size_bytes, a.width asset_width, a.height asset_height,
                               a.url asset_url, a.expires_at asset_expires_at, a.created_at asset_created_at
                        from projects j join products p on p.id = j.product_id
                        left join assets a on a.id = coalesce(j.preview_asset_id, p.source_asset_id)
                        where j.id = ?
                        """,
                        (result, row) -> new OwnedProject(
                                mapProject(result),
                                result.getString("owner_user_id"),
                                result.getString("owner_trial_session_id")),
                        projectId)
                .stream()
                .filter(project -> project.ownedBy(owner))
                .map(OwnedProject::project)
                .findFirst()
                .orElseThrow(ProjectException::projectNotFound);
    }

    @Transactional(readOnly = true)
    ProjectPageView listOwned(String userId, String encodedCursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw ProjectException.badRequest("Limit must be between 1 and 100");
        }
        Cursor cursor = decodeCursor(encodedCursor);
        String select = """
                select j.id, j.name, j.product_id, j.selected_template_id,
                       j.default_aspect_ratio, j.card_count, j.created_at, j.updated_at,
                       a.id asset_id, a.purpose asset_purpose, a.media_type asset_media_type,
                       a.size_bytes asset_size_bytes, a.width asset_width, a.height asset_height,
                       a.url asset_url, a.expires_at asset_expires_at, a.created_at asset_created_at
                from projects j
                join products p on p.id = j.product_id
                left join assets a on a.id = coalesce(j.preview_asset_id, p.source_asset_id)
                where p.owner_user_id = ?
                """;
        List<ProjectView> fetched;
        if (cursor == null) {
            fetched = jdbc.query(
                    select + " order by j.updated_at desc, j.id desc limit ?",
                    (result, row) -> mapProject(result),
                    userId,
                    limit + 1);
        } else {
            fetched = jdbc.query(
                    select
                            + " and (j.updated_at < ? or (j.updated_at = ? and j.id < ?))"
                            + " order by j.updated_at desc, j.id desc limit ?",
                    (result, row) -> mapProject(result),
                    userId,
                    Timestamp.from(cursor.updatedAt()),
                    Timestamp.from(cursor.updatedAt()),
                    cursor.id(),
                    limit + 1);
        }
        boolean hasNext = fetched.size() > limit;
        List<ProjectView> items = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
        String nextCursor = hasNext ? encodeCursor(items.get(items.size() - 1)) : null;
        return new ProjectPageView(List.copyOf(items), limit, nextCursor);
    }

    private Product findProduct(String productId) {
        return jdbc.query(
                        "select name, owner_user_id, owner_trial_session_id from products where id = ?",
                        (result, row) -> new Product(
                                result.getString("name"),
                                result.getString("owner_user_id"),
                                result.getString("owner_trial_session_id")),
                        productId)
                .stream()
                .findFirst()
                .orElseThrow(ProjectException::productNotFound);
    }

    private ProjectView mapProject(ResultSet result) throws SQLException {
        return new ProjectView(
                result.getString("id"),
                result.getString("name"),
                result.getString("product_id"),
                result.getString("selected_template_id"),
                result.getString("default_aspect_ratio"),
                result.getInt("card_count"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                mapAsset(result));
    }

    private ProjectAssetView mapAsset(ResultSet result) throws SQLException {
        String id = result.getString("asset_id");
        if (id == null) {
            return null;
        }
        Timestamp expiresAt = result.getTimestamp("asset_expires_at");
        return new ProjectAssetView(
                id,
                result.getString("asset_purpose"),
                result.getString("asset_media_type"),
                result.getInt("asset_size_bytes"),
                (Integer) result.getObject("asset_width"),
                (Integer) result.getObject("asset_height"),
                URI.create(result.getString("asset_url")),
                expiresAt == null ? null : expiresAt.toInstant(),
                result.getTimestamp("asset_created_at").toInstant());
    }

    private Cursor decodeCursor(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            if (encoded.isBlank() || encoded.length() > 1024) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\n", -1);
            if (values.length != 3 || !"v1".equals(values[0]) || !values[2].matches("^prj_[A-Za-z0-9]+$")) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Instant.parse(values[1]), values[2]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw ProjectException.badRequest("Cursor is invalid");
        }
    }

    private String encodeCursor(ProjectView project) {
        String value = "v1\n" + project.updatedAt() + "\n" + project.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant updatedAt, String id) {}

    private record Product(String name, String userId, String trialSessionId) {
        boolean ownedBy(ProjectOwner owner) {
            return java.util.Objects.equals(userId, owner.userId())
                    && java.util.Objects.equals(trialSessionId, owner.trialSessionId());
        }
    }

    private record OwnedProject(ProjectView project, String userId, String trialSessionId) {
        boolean ownedBy(ProjectOwner owner) {
            return java.util.Objects.equals(userId, owner.userId())
                    && java.util.Objects.equals(trialSessionId, owner.trialSessionId());
        }
    }
}
