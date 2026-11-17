package com.tovarika.tech.products.infrastructure;

import com.tovarika.tech.products.application.ProductStore;
import com.tovarika.tech.products.domain.ProductView;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductStore implements ProductStore {
    private final JdbcTemplate jdbc;
    public JdbcProductStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void reserve(String key, Instant now) {
        jdbc.update("insert into product_uploads(storage_key, created_at) values (?, ?)", key, Timestamp.from(now));
    }
    public void lockReservation(String key) {
        jdbc.queryForObject("select storage_key from product_uploads where storage_key = ? for update", String.class, key);
    }
    public void create(ProductView p, String userId, String trialId) {
        // Serialize creation against trial conversion. A stale resolver must not leave resources on a converted owner.
        if (trialId != null) {
            var active = jdbc.queryForList("select id from trial_sessions where id = ? and owner_user_id is null and expires_at > current_timestamp for update", String.class, trialId);
            if (active.isEmpty()) throw new com.tovarika.tech.shared.application.ApiFailure(401, "TRIAL_SESSION_NOT_FOUND", "Trial session unavailable");
        }
        var a = p.asset();
        insertAsset(a);
        jdbc.update("""
                insert into products(id, name, owner_user_id, owner_trial_session_id, source_asset_id, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'uploaded', ?, ?)
                """, p.id(), p.name(), userId, trialId, a.id(), Timestamp.from(p.createdAt()), Timestamp.from(p.updatedAt()));
        linkImage(p.id(), a);
    }
    private void insertAsset(ProductView.Asset a) {
        jdbc.update("""
                insert into assets(id, purpose, media_type, size_bytes, width, height, url, storage_key, created_at)
                values (?, 'source_image', ?, ?, ?, ?, '', ?, ?)
                """, a.id(), a.mediaType(), a.size(), a.width(), a.height(), a.storageKey(), Timestamp.from(a.createdAt()));
    }
    private void linkImage(String productId, ProductView.Asset asset) {
        jdbc.update("insert into product_images(product_id, asset_id, created_at) values (?, ?, ?)",
                productId, asset.id(), Timestamp.from(asset.createdAt()));
    }
    public void lockOwned(String productId, String userId, String trialId) {
        // Match the conversion lock order: trial identity before its resources.
        if (trialId != null && jdbc.queryForList("""
                select id from trial_sessions where id=? and owner_user_id is null
                and expires_at > current_timestamp for update
                """, String.class, trialId).isEmpty()) {
            throw new com.tovarika.tech.shared.application.ApiFailure(401, "TRIAL_SESSION_NOT_FOUND", "Trial session unavailable");
        }
        if (jdbc.queryForList("""
                select id from products where id=? and (owner_user_id=? or owner_trial_session_id=?) for update
                """, String.class, productId, userId, trialId).isEmpty()) {
            throw new com.tovarika.tech.shared.application.ApiFailure(404, "PRODUCT_NOT_FOUND", "Product not found");
        }
    }
    public void addImage(String productId, ProductView.Asset asset) {
        insertAsset(asset);
        linkImage(productId, asset);
        jdbc.update("update products set updated_at=? where id=?", Timestamp.from(asset.createdAt()), productId);
    }
    public List<ProductView.Asset> images(String productId, String userId, String trialId) {
        return jdbc.query("""
                select a.* from product_images i join assets a on a.id=i.asset_id
                join products p on p.id=i.product_id
                where p.id=? and (p.owner_user_id=? or p.owner_trial_session_id=?)
                order by (a.id=p.source_asset_id) desc, i.created_at, a.id
                """, (r,n) -> asset(r), productId, userId, trialId);
    }
    public void release(String key) { jdbc.update("delete from product_uploads where storage_key = ?", key); }
    public boolean hasAsset(String key) { return !jdbc.queryForList("select id from assets where storage_key = ?", String.class, key).isEmpty(); }
    public List<String> abandoned(Instant before) {
        return jdbc.queryForList("select storage_key from product_uploads where created_at < ? order by created_at limit 100 for update skip locked", String.class, Timestamp.from(before));
    }
    public Optional<ProductView> findOwned(String id, String userId, String trialId) {
        return jdbc.query("""
                select p.id as product_id, p.name, p.status, p.analysis_job_id, p.created_at as product_created,
                    p.updated_at, a.* from products p join assets a on a.id = p.source_asset_id
                where p.id = ? and (p.owner_user_id = ? or p.owner_trial_session_id = ?)
                """, (r, n) -> new ProductView(r.getString("product_id"), r.getString("name"), r.getString("status"),
                r.getString("analysis_job_id"), asset(r), r.getTimestamp("product_created").toInstant(),
                r.getTimestamp("updated_at").toInstant()), id, userId, trialId).stream().findFirst();
    }
    public Optional<ProductView.Asset> findAsset(String id) {
        return jdbc.query("select * from assets where id = ? and storage_key is not null", (r,n) -> asset(r), id).stream().findFirst();
    }
    private ProductView.Asset asset(ResultSet r) throws SQLException {
        return new ProductView.Asset(r.getString("id"), r.getString("media_type"), r.getInt("size_bytes"),
                r.getObject("width", Integer.class), r.getObject("height", Integer.class), r.getString("storage_key"),
                r.getTimestamp("created_at").toInstant());
    }
}
