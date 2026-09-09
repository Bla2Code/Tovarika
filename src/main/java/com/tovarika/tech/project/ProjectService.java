package com.tovarika.tech.project;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
                               p.owner_user_id, p.owner_trial_session_id
                        from projects j join products p on p.id = j.product_id
                        where j.id = ?
                        """,
                        (result, row) -> new OwnedProject(
                                new ProjectView(
                                        result.getString("id"),
                                        result.getString("name"),
                                        result.getString("product_id"),
                                        result.getString("selected_template_id"),
                                        result.getString("default_aspect_ratio"),
                                        result.getInt("card_count"),
                                        result.getTimestamp("created_at").toInstant(),
                                        result.getTimestamp("updated_at").toInstant()),
                                result.getString("owner_user_id"),
                                result.getString("owner_trial_session_id")),
                        projectId)
                .stream()
                .filter(project -> project.ownedBy(owner))
                .map(OwnedProject::project)
                .findFirst()
                .orElseThrow(ProjectException::projectNotFound);
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
