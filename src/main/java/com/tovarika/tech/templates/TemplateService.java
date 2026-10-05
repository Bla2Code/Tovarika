package com.tovarika.tech.templates;

import com.tovarika.tech.shared.application.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TemplateService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TemplateCategoryView> categories() {
        return jdbc.query("""
                select c.id, c.name from template_categories c
                where c.available = true
                  and exists(select 1 from templates t where t.category_id = c.id and t.available = true)
                order by c.sort_order, c.id
                """, (result, row) -> new TemplateCategoryView(result.getString("id"), result.getString("name")));
    }

    @Transactional(readOnly = true)
    public TemplatePageView list(
            String userId, String categoryId, String search, boolean favoriteOnly, String encodedCursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Limit must be between 1 and 100");
        }
        if (favoriteOnly && userId == null) {
            throw new ApiFailure(401, "AUTHENTICATION_REQUIRED", "Authentication is required for favorites");
        }
        if (categoryId != null && jdbc.queryForList(
                "select id from template_categories where id = ? and available = true", String.class, categoryId).isEmpty()) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Template category is invalid");
        }
        Cursor cursor = decodeCursor(encodedCursor);
        StringBuilder sql = new StringBuilder("""
                select t.id, t.name, t.category_id, t.reference_asset_id,
                       c.sort_order category_order, t.sort_order,
                """);
        List<Object> parameters = new ArrayList<>();
        if (userId == null) {
            sql.append(" false favorite ");
        } else {
            sql.append(" exists(select 1 from template_favorites f where f.template_id=t.id and f.user_id=?) favorite ");
            parameters.add(userId);
        }
        sql.append("""
                from templates t join template_categories c on c.id=t.category_id
                where t.available=true and c.available=true
                """);
        if (categoryId != null) {
            sql.append(" and t.category_id=? ");
            parameters.add(categoryId);
        }
        if (search != null) {
            String normalized = search.strip();
            if (normalized.isEmpty() || normalized.length() > 200) {
                throw new ApiFailure(400, "VALIDATION_ERROR", "Search must contain 1 to 200 characters");
            }
            sql.append(" and lower(t.name) like ? escape '\\' ");
            parameters.add("%" + escapeLike(normalized.toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        if (favoriteOnly) {
            sql.append(" and exists(select 1 from template_favorites f where f.template_id=t.id and f.user_id=?) ");
            parameters.add(userId);
        }
        if (cursor != null) {
            sql.append(" and (c.sort_order,t.sort_order,t.id) > (?,?,?) ");
            parameters.add(cursor.categoryOrder());
            parameters.add(cursor.sortOrder());
            parameters.add(cursor.id());
        }
        sql.append(" order by c.sort_order,t.sort_order,t.id limit ? ");
        parameters.add(limit + 1);
        List<TemplateView> fetched = jdbc.query(sql.toString(), this::map, parameters.toArray());
        boolean hasNext = fetched.size() > limit;
        List<TemplateView> items = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        String next = hasNext ? encodeCursor(items.get(items.size() - 1)) : null;
        return new TemplatePageView(items, limit, next);
    }

    @Transactional
    public TemplateView setFavorite(String userId, String templateId, boolean favorite) {
        if (userId == null) {
            throw new ApiFailure(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        if (jdbc.queryForList("select id from templates where id=? and available=true", String.class, templateId).isEmpty()) {
            throw new ApiFailure(404, "TEMPLATE_NOT_FOUND", "Template not found");
        }
        if (favorite) {
            jdbc.update("""
                    insert into template_favorites(user_id,template_id,created_at) values(?,?,?)
                    on conflict(user_id,template_id) do nothing
                    """, userId, templateId, Timestamp.from(clock.instant()));
        } else {
            jdbc.update("delete from template_favorites where user_id=? and template_id=?", userId, templateId);
        }
        return jdbc.query("""
                select t.id,t.name,t.category_id,t.reference_asset_id,c.sort_order category_order,t.sort_order,
                       exists(select 1 from template_favorites f where f.template_id=t.id and f.user_id=?) favorite
                from templates t join template_categories c on c.id=t.category_id where t.id=?
                """, this::map, userId, templateId).getFirst();
    }

    @Transactional(readOnly = true)
    public Optional<TemplateGenerationView> generationTemplate(String id) {
        return jdbc.query("""
                select t.id,t.recipe_json::text,t.reference_asset_id,a.storage_key,a.media_type
                from templates t left join assets a on a.id=t.reference_asset_id
                where t.id=? and t.available=true
                """, (result, row) -> new TemplateGenerationView(
                        result.getString("id"), result.getString("recipe_json"),
                        result.getString("reference_asset_id"), result.getString("storage_key"),
                        result.getString("media_type")), id).stream().findFirst();
    }

    private TemplateView map(ResultSet result, int row) throws SQLException {
        return new TemplateView(
                result.getString("id"), result.getString("name"), result.getString("category_id"),
                result.getString("reference_asset_id"), result.getBoolean("favorite"),
                result.getInt("category_order"), result.getInt("sort_order"));
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Cursor decodeCursor(String value) {
        if (value == null) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\n", -1);
            if (fields.length != 4 || !"v1".equals(fields[0]) || !fields[3].matches("^tpl_[A-Za-z0-9_]+$")) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3]);
        } catch (IllegalArgumentException invalid) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Cursor is invalid");
        }
    }

    private String encodeCursor(TemplateView template) {
        String raw = "v1\n" + template.categoryOrder() + "\n" + template.sortOrder() + "\n" + template.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(int categoryOrder, int sortOrder, String id) {}
}
