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
class CardImageVersionMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");

    @Test void upgradeBackfillsOriginalAndEnforcesCardVersionOwnershipAndDeleteCascade() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try(var liquibase=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),database)) {
                liquibase.update(14,new Contexts(),new LabelExpression());
                try(var sql=connection.createStatement()) {
                    sql.execute("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_old','old@example.test',true,'ACTIVE',now(),now())");
                    sql.execute("insert into products(id,name,owner_user_id,created_at,updated_at) values('prd_old','Old','usr_old',now(),now())");
                    sql.execute("insert into projects(id,product_id,name,default_aspect_ratio,card_count,created_at,updated_at) values('prj_old','prd_old','Old','3:4',2,now(),now())");
                    sql.execute("insert into assets(id,purpose,media_type,size_bytes,width,height,url,storage_key,created_at) values('asset_old','card_image','image/png',100,1152,1536,'','cards/old',now())");
                    sql.execute("insert into cards(id,project_id,position,status,aspect_ratio,prompt,image_asset_id,created_at,updated_at) values('card_old','prj_old',1,'ready','3:4','saved prompt','asset_old',now(),now()),('card_empty','prj_old',2,'error','3:4','failed prompt',null,now(),now())");
                }
                connection.commit();
                liquibase.update(1,new Contexts(),new LabelExpression());
                try(var sql=connection.createStatement()) {
                    sql.execute("insert into card_image_undo_receipts(owner_scope,idempotency_key,project_id,card_id,command_digest,created_at) values('usr_old','old-undo-key','prj_old','card_old','old_digest',now())");
                }
                connection.commit();
                liquibase.update(new Contexts(),new LabelExpression());
                liquibase.update(new Contexts(),new LabelExpression());
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select c.image_revision,c.current_version_id,v.image_asset_id,v.previous_version_id,c.prompt from cards c join card_image_versions v on v.id=c.current_version_id where c.id='card_old'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).startsWith("ver_");assertThat(rows.getString(3)).isEqualTo("asset_old");
                    assertThat(rows.getString(4)).isNull();assertThat(rows.getString(5)).isEqualTo("saved prompt");
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select image_revision,current_version_id from cards where id='card_empty'")) {
                    rows.next();assertThat(rows.getLong(1)).isZero();assertThat(rows.getString(2)).isNull();
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select command_digest from card_image_history_receipts where idempotency_key='old-undo-key'")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("old_digest");
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select count(*) from card_image_redo_stack")) {
                    rows.next();assertThat(rows.getInt(1)).isZero();
                }
                connection.setAutoCommit(true);
                assertThatThrownBy(()->{try(var sql=connection.createStatement()) {
                    sql.execute("update cards set current_version_id=(select current_version_id from cards where id='card_old') where id='card_empty'");
                }}).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->{try(var sql=connection.createStatement()) {
                    sql.execute("insert into card_image_redo_stack(card_id,position,version_id) select 'card_empty',1,current_version_id from cards where id='card_old'");
                }}).isInstanceOf(java.sql.SQLException.class);
                try(var sql=connection.createStatement()) {
                    sql.execute("insert into card_image_redo_stack(card_id,position,version_id) select id,1,current_version_id from cards where id='card_old'");
                    sql.execute("delete from projects where id='prj_old'");
                }
                try(var sql=connection.createStatement();var rows=sql.executeQuery("select count(*) from card_image_versions")) {
                    rows.next();assertThat(rows.getInt(1)).isZero();
                }
                for(String table:java.util.List.of("card_image_redo_stack","card_image_history_receipts")) {
                    try(var sql=connection.createStatement();var rows=sql.executeQuery("select count(*) from "+table)) {
                        rows.next();assertThat(rows.getInt(1)).isZero();
                    }
                }
            }
        }
    }
}
