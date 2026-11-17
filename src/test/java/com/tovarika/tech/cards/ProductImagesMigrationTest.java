package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProductImagesMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Test void upgradePreservesProductsCardsJobsAndOriginalFiles() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts();
                var labels = new LabelExpression();
                int previousChanges = (int) liquibase.listUnrunChangeSets(contexts, labels).stream()
                        .takeWhile(change -> !"085-product-images".equals(change.getId())).count();
                liquibase.update(previousChanges, contexts, labels);
                try (var sql = connection.createStatement()) {
                    sql.execute("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_old_images','images@example.test',true,'ACTIVE',now(),now())");
                    sql.execute("insert into assets(id,purpose,media_type,size_bytes,url,storage_key,created_at) values('asset_old_image','source_image','image/png',123,'','products/original-unchanged',now())");
                    sql.execute("insert into products(id,name,owner_user_id,status,source_asset_id,created_at,updated_at) values('prd_old_images','Existing product','usr_old_images','analysis_ready','asset_old_image',now(),now())");
                    sql.execute("insert into projects(id,product_id,name,default_aspect_ratio,card_count,created_at,updated_at) values('prj_old_images','prd_old_images','Existing project','3:4',2,now(),now())");
                    sql.execute("insert into cards(id,project_id,position,status,aspect_ratio,prompt,created_at,updated_at) values('card_old_ready','prj_old_images',1,'ready','3:4','saved prompt',now(),now()),('card_old_failed','prj_old_images',2,'error','3:4','retry prompt',now(),now())");
                    sql.execute("insert into project_jobs(id,project_id,card_id,type,status,created_at,updated_at) values('job_old_images','prj_old_images','card_old_failed','card_generation','failed',now(),now())");
                }
                liquibase.update(contexts, labels);
                liquibase.update(contexts, labels);
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("select count(*) from product_images where product_id='prd_old_images' and asset_id='asset_old_image'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("select id,status,prompt,source_asset_id from cards where project_id='prj_old_images' order by position")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("id")).isEqualTo("card_old_ready");
                    assertThat(rows.getString("status")).isEqualTo("ready");
                    assertThat(rows.getString("prompt")).isEqualTo("saved prompt");
                    assertThat(rows.getString("source_asset_id")).isEqualTo("asset_old_image");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("error");
                    assertThat(rows.getString("prompt")).isEqualTo("retry prompt");
                    assertThat(rows.getString("source_asset_id")).isEqualTo("asset_old_image");
                }
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("select p.status,a.storage_key,a.size_bytes,j.card_count,q.status,q.progress from products p join assets a on a.id=p.source_asset_id join projects j on j.product_id=p.id join project_jobs q on q.project_id=j.id where p.id='prd_old_images'")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("analysis_ready");
                    assertThat(rows.getString(2)).isEqualTo("products/original-unchanged");
                    assertThat(rows.getInt(3)).isEqualTo(123);
                    assertThat(rows.getInt(4)).isEqualTo(2);
                    assertThat(rows.getString(5)).isEqualTo("failed");
                    assertThat(rows.getInt(6)).isZero();
                }
            }
        }
    }
}
