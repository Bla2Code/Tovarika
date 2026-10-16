package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class CardSeriesMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Test void upgradePreservesExistingFirstCardAndRepeatedLiquibaseDoesNotDuplicateVariants() throws Exception {
        try (var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),database)) {
                // Twelve published changesets: 000–011, before the series schema.
                liquibase.update(12,new Contexts(),new LabelExpression());
                try(var sql=connection.createStatement()) {
                    sql.execute("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_old','old@example.test',true,'ACTIVE',now(),now())");
                    sql.execute("insert into products(id,name,owner_user_id,created_at,updated_at) values('prd_old','Old','usr_old',now(),now())");
                    sql.execute("insert into projects(id,product_id,name,default_aspect_ratio,card_count,created_at,updated_at) values('prj_old','prd_old','Old','3:4',1,now(),now())");
                    sql.execute("insert into cards(id,project_id,position,status,aspect_ratio,template_id,prompt,recipe_snapshot,reference_asset_snapshot_id,created_at,updated_at) select 'card_old','prj_old',1,'ready','3:4',id,'saved original prompt',recipe_json,reference_asset_id,now(),now() from templates where id='tpl_fashion_hero'");
                }
                liquibase.update(new Contexts(),new LabelExpression());
                liquibase.update(new Contexts(),new LabelExpression());
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select count(*) from template_card_variants where template_id='tpl_fashion_hero'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(10);
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select t.reference_asset_id,a.storage_key,a.size_bytes from templates t join assets a on a.id=t.reference_asset_id where t.id='tpl_fashion_hero'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("asset_tpl_fashion_hero_ref_v3");
                    assertThat(rows.getString(2)).isEqualTo("templates/tpl_fashion_hero/reference-v3.webp");
                    assertThat(rows.getLong(3)).isEqualTo(26576);
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select generation_recipe->>'composition' from template_card_variants where id='tpl_fashion_hero_v01'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).contains("до двух карточек различимых деталей снизу");
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select prompt,status,variant_snapshot,card_series_snapshot,reference_asset_snapshot_id from cards join projects on projects.id=cards.project_id where cards.id='card_old'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("saved original prompt");
                    assertThat(rows.getString(2)).isEqualTo("ready");
                    assertThat(rows.getString(3)).isNull(); assertThat(rows.getString(4)).isNull();
                    assertThat(rows.getString(5)).isEqualTo("asset_tpl_fashion_hero_ref");
                }
                connection.setAutoCommit(true);
                assertThatThrownBy(()->{try(var sql=connection.createStatement()) { sql.execute("insert into template_card_variants select 'duplicate_variant',template_id,position,title,default_idea,generation_recipe from template_card_variants where position=1"); }})
                    .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->{try(var sql=connection.createStatement()) { sql.execute("insert into template_card_variants select 'foreign_variant','missing_template',1,title,default_idea,generation_recipe from template_card_variants where position=1"); }})
                    .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
