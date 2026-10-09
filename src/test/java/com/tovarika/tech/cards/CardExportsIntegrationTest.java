package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.cards.editing.application.CardImageEditingService;
import com.tovarika.tech.exports.*;
import com.tovarika.tech.products.application.AssetLinks;
import com.tovarika.tech.shared.application.ApiFailure;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static com.tovarika.tech.exports.ExportSnapshot.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.password.breached-check-enabled=false", "tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.cards.worker-enabled=false", "tovarika.analysis.worker-enabled=false", "tovarika.exports.worker-enabled=false",
    "tovarika.ai.openai.mode=stub"
})
@org.springframework.context.annotation.Import(CardSeriesStorageConfiguration.class)
class CardExportsIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @DynamicPropertySource static void testKey(DynamicPropertyRegistry registry) {
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        registry.add("tovarika.security.jwt.secret-base64",()->Base64.getEncoder().encodeToString(key));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ExportService exports;
    @Autowired ExportStore exportStore;
    @Autowired ExportWorker worker;
    @Autowired CardGenerationStore cards;
    @Autowired CardImageEditingService edits;
    @Autowired CardSeriesStorageConfiguration.MemoryStorage storage;
    @Autowired TransactionTemplate tx;
    @Autowired AssetLinks links;
    @Autowired ObjectMapper mapper;
    @Autowired AuthenticationStore auth;
    @Autowired SessionService sessions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String authorization;
    byte[] original,current,second;

    @BeforeEach void setup() throws Exception {
        storage.data.clear();storage.failReads=false;
        jdbc.execute("truncate users,trial_sessions,products,projects,product_analyses,project_jobs,cards,export_files cascade");
        jdbc.update("delete from assets where id like 'asset_export%'");
        original=png(0xff226688);current=png(0x55224466);second=png(0xff886644);
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_export','export@example.test',true,'ACTIVE',now(),now()),('usr_other','other-export@example.test',true,'ACTIVE',now(),now())");
        asset("asset_exportSource","source_image",original);
        asset("asset_exportMask","region_mask",second);
        jdbc.update("insert into products(id,name,owner_user_id,status,source_asset_id,created_at,updated_at) values('prd_export','Mug','usr_export','analysis_ready','asset_exportSource',now(),now())");
        jdbc.update("insert into projects(id,product_id,name,default_aspect_ratio,card_count,preview_asset_id,created_at,updated_at) values('prj_export','prd_export','Export project','3:4',2,'asset_exportOriginal',now(),now())".replace("'asset_exportOriginal'","null"));
        asset("asset_exportOriginal","card_image",original);asset("asset_exportCurrent","card_image",current);asset("asset_exportSecond","card_image",second);
        tx.executeWithoutResult(t->{
            jdbc.update("insert into cards(id,project_id,position,status,aspect_ratio,prompt,created_at,updated_at) values('card_A','prj_export',1,'ready','3:4','prompt',now(),now()),('card_B','prj_export',2,'ready','3:4','prompt',now(),now())");
            jdbc.update("insert into card_image_versions(id,card_id,image_asset_id,aspect_ratio,created_at) values('ver_A','card_A','asset_exportOriginal','3:4',now()),('ver_B','card_B','asset_exportSecond','3:4',now())");
            jdbc.update("insert into card_image_versions(id,card_id,previous_version_id,image_asset_id,aspect_ratio,created_at) values('ver_Aedit','card_A','ver_A','asset_exportCurrent','3:4',now())");
            jdbc.update("update cards set current_version_id='ver_Aedit',image_asset_id='asset_exportCurrent',image_revision=2 where id='card_A'");
            jdbc.update("update cards set current_version_id='ver_B',image_asset_id='asset_exportSecond',image_revision=1 where id='card_B'");
        });
        jdbc.update("update projects set preview_asset_id='asset_exportOriginal' where id='prj_export'");
        authorization="Bearer "+sessions.create(auth.findUserById("usr_export").orElseThrow(),new RequestMetadata("Test Browser","192.0.2.xxx")).accessToken();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() {storage.failReads=false;}
    private byte[] png(int color) throws Exception {
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,color);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
    private void asset(String id,String purpose,byte[] bytes) {
        storage.put(id,bytes,"image/png");
        jdbc.update("insert into assets(id,purpose,media_type,size_bytes,width,height,has_alpha,url,storage_key,created_at) values(?,?,'image/png',?,8,8,true,'',?,now())",id,purpose,bytes.length,id);
    }
    private Request request(String... ids) {
        var expected=Arrays.stream(ids).map(id->{var card=cards.ownedCard("prj_export",id,"usr_export",null).orElseThrow();
            return new Expectation(id,card.currentVersionId(),card.imageRevision());}).toList();
        return new Request(List.of(ids),"original",ids.length==1?"single":"zip",expected);
    }
    private Export start(String key,String... ids) {return exports.start("prj_export",key,"usr_export",request(ids));}
    private Map<String,byte[]> unzip(byte[] bytes) throws Exception {
        var result=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null) result.put(entry.getName(),zip.readAllBytes());
        }
        return result;
    }
    @Test void zipFixesCurrentVersionsAndReplayBeforeUndoAndDoesNotBlockHistory() throws Exception {
        Request selection=request("card_B","card_A");
        var accepted=exports.start("prj_export","snapshot-key","usr_export",selection);
        assertThat(cards.busy("prj_export")).isFalse();
        assertThat(cards.ownedCard("prj_export","card_A","usr_export",null).orElseThrow().canUndo()).isTrue();
        edits.undo("prj_export","card_A","undo-export-key","usr_export",null,"ver_Aedit",2);
        assertThat(exports.start("prj_export","snapshot-key","usr_export",selection).id()).isEqualTo(accepted.id());
        assertThat(worker.runOnce()).isTrue();
        var ready=exports.get(accepted.id(),"usr_export");
        assertThat(ready.items()).extracting(ExportSnapshot::versionId).containsExactly("ver_Aedit","ver_B");
        var zip=unzip(storage.read(exportStore.artifact(ready.artifactId()).storageKey()));
        assertThat(zip.keySet()).containsExactly("card-01.png","card-02.png");
        assertThat(zip.get("card-01.png")).isEqualTo(current);
        assertThat(zip.get("card-02.png")).isEqualTo(second);
        var next=start("single-after-undo","card_A");worker.runOnce();
        assertThat(exports.get(next.id(),"usr_export").artifactId()).isEqualTo("asset_exportOriginal");
        assertThatThrownBy(()->exports.start("prj_export","stale-selection","usr_export",selection)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("CARD_VERSION_CONFLICT");
        var staleOriginal=new Request(List.of("card_A"),"original","single",List.of(new Expectation("card_A","ver_A",1L)));
        assertThatThrownBy(()->exports.start("prj_export","aba-selection","usr_export",staleOriginal)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("CARD_VERSION_CONFLICT");
        edits.redo("prj_export","card_A","redo-export-key","usr_export",null,"ver_A",3);
        var redone=start("single-after-redo","card_A");worker.runOnce();
        assertThat(exports.get(redone.id(),"usr_export").artifactId()).isEqualTo("asset_exportCurrent");
    }
    @Test void httpCreatesExportAndDownloadsExactSingleWithAttachmentAndChecksOwnership() throws Exception {
        var response=mvc.perform(post("/api/v1/projects/prj_export/exports").header("Authorization",authorization)
                .header("Idempotency-Key","http-export-key").contentType("application/json").content(mapper.writeValueAsString(request("card_A"))))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var operation=mapper.readTree(response.getContentAsString());String id=operation.path("target").path("id").asText();
        worker.runOnce();
        mvc.perform(get("/api/v1/jobs/"+operation.path("jobId").asText()).header("Authorization",authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("export")).andExpect(jsonPath("$.target.id").value(id));
        mvc.perform(get("/api/v1/exports/"+id).header("Authorization",authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.purpose").value("card_image")).andExpect(jsonPath("$.items[0].versionId").value("ver_Aedit"));
        var link=links.createExport(id,Instant.now().plusSeconds(86400));var uri=URI.create(link.url());
        mvc.perform(get(uri.getPath()+"?"+uri.getRawQuery())).andExpect(status().isOk()).andExpect(content().bytes(current))
                .andExpect(header().string("Content-Type","image/png")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")));
        assertThatThrownBy(()->exports.get(id,"usr_other")).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("EXPORT_NOT_FOUND");
        assertThat(exports.findJob(operation.path("jobId").asText(),"usr_other")).isEmpty();
        assertThatThrownBy(()->exports.start("prj_export","trial-key",null,request("card_A"))).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("REGISTRATION_REQUIRED");
        mvc.perform(get(uri.getPath()+"?expires=1&signature=invalid")).andExpect(status().isForbidden());
    }
    @Test void originalPreservesJpegBytesAndMixedZipExtensions() throws Exception {
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,0xff335577);
        var output=new ByteArrayOutputStream();
        assertThat(ImageIO.write(image,"jpg",output)).isTrue();
        byte[] jpeg=output.toByteArray();
        storage.put("asset_exportSecond",jpeg,"image/jpeg");
        jdbc.update("update assets set media_type='image/jpeg',size_bytes=?,has_alpha=false where id='asset_exportSecond'",jpeg.length);
        var multiple=start("mixed-formats","card_A","card_B");
        worker.runOnce();
        var ready=exports.get(multiple.id(),"usr_export");
        var files=unzip(storage.read(exportStore.artifact(ready.artifactId()).storageKey()));
        assertThat(files.keySet()).containsExactly("card-01.png","card-02.jpg");
        assertThat(files.get("card-01.png")).isEqualTo(current);
        assertThat(files.get("card-02.jpg")).isEqualTo(jpeg);
        var single=start("original-jpeg","card_B");
        worker.runOnce();
        ready=exports.get(single.id(),"usr_export");
        assertThat(ready.artifactId()).isEqualTo("asset_exportSecond");
        assertThat(ready.fileName()).isEqualTo("card-02.jpg");
        var link=links.createExport(ready.id(),ready.expiresAt());var uri=URI.create(link.url());
        mvc.perform(get(uri.getPath()+"?"+uri.getRawQuery())).andExpect(status().isOk())
                .andExpect(content().bytes(jpeg)).andExpect(header().string("Content-Type","image/jpeg"));
    }
    @Test void rawHttpRequestRejectsDuplicateIdsAndFractionalRevisionBeforeDtoConversion() throws Exception {
        for(String body:List.of(
                """
                {"cardIds":["card_A","card_A"],"format":"original","packaging":"zip"}
                """,
                """
                {"cardIds":["card_A"],"format":"original","packaging":"single",
                 "expectedImages":[{"cardId":"card_A","versionId":"ver_Aedit","imageRevision":2.5}]}
                """)) {
            mvc.perform(post("/api/v1/projects/prj_export/exports").header("Authorization",authorization)
                    .header("Idempotency-Key","invalid-raw-request").contentType("application/json").content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.queryForObject("select count(*) from card_exports",Integer.class)).isZero();
    }
    @Test void rejectsBusyMissingWrongPurposeAndInvalidSelectionsBeforeCreatingExport() {
        Request selection=request("card_A");
        for(String status:List.of("generating","regenerating","error")) {
            jdbc.update("update cards set status=? where id='card_A'",status);
            assertThatThrownBy(()->exports.start("prj_export","status-"+status,"usr_export",selection)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("RESULT_NOT_READY");
        }
        jdbc.update("update cards set status='ready' where id='card_A'");
        jdbc.update("update assets set purpose='source_image' where id='asset_exportCurrent'");
        assertThatThrownBy(()->exports.start("prj_export","wrong-purpose","usr_export",selection)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("RESULT_NOT_READY");
        assertThatThrownBy(()->exports.start("prj_export","foreign-key","usr_other",selection)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("PROJECT_NOT_FOUND");
        for(var invalid:List.of(new Request(List.of(),"original","single",null),new Request(List.of("card_A","card_A"),"original","zip",null),
                new Request(List.of("card_A"),"original","zip",null),new Request(List.of("card_A","card_B"),"original","single",null),
                new Request(List.of("card_A"),"original","single",List.of(new Expectation("card_B","ver_B",1L))))) {
            assertThatThrownBy(()->exports.start("prj_export","invalid-key","usr_export",invalid)).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        }
        assertThat(jdbc.queryForObject("select count(*) from card_exports",Integer.class)).isZero();
    }
    @Test void failurePublishesNoPartialArtifactAndNewIntentCanRetry() {
        var first=start("fail-key","card_A","card_B");storage.failReads=true;
        assertThat(worker.runOnce()).isTrue();storage.failReads=false;
        var failed=exports.get(first.id(),"usr_export");
        assertThat(failed.status()).isEqualTo("failed");assertThat(failed.artifactId()).isNull();
        assertThat(exports.findJob(first.jobId(),"usr_export").orElseThrow().failureCode()).isEqualTo("EXPORT_FAILED");
        var next=start("retry-new-key","card_A","card_B");worker.runOnce();
        assertThat(exports.get(next.id(),"usr_export").status()).isEqualTo("completed");
        assertThatThrownBy(()->exports.start("prj_export","retry-new-key","usr_export",request("card_A"))).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void expiryAndProjectDeletionCleanOnlyExportFilesAndInvalidateLinks() throws Exception {
        var value=start("expiry-key","card_A","card_B");worker.runOnce();
        var ready=exports.get(value.id(),"usr_export");String key=exportStore.artifact(ready.artifactId()).storageKey();
        jdbc.update("update card_exports set expires_at=now()-interval '1 second' where id=?",value.id());
        assertThatThrownBy(()->exports.get(value.id(),"usr_export")).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("EXPORT_EXPIRED");
        worker.cleanup();assertThat(storage.data).doesNotContainKey(key).containsKeys("asset_exportSource","asset_exportOriginal","asset_exportCurrent","asset_exportSecond");
        assertThat(jdbc.queryForObject("select count(*) from assets where id=?",Integer.class,ready.artifactId())).isZero();
        var active=start("delete-active-key","card_A","card_B");
        mvc.perform(delete("/api/v1/projects/prj_export").header("Authorization",authorization)).andExpect(status().isNoContent());
        assertThat(worker.runOnce()).isFalse();
        assertThatThrownBy(()->exports.get(active.id(),"usr_export")).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("EXPORT_NOT_FOUND");
        var link=links.createExport(value.id(),Instant.now().plusSeconds(900));var uri=URI.create(link.url());
        mvc.perform(get(uri.getPath()+"?"+uri.getRawQuery())).andExpect(status().isNotFound());
    }
    @Test void reclaimsLeaseAndIgnoresOldAttemptAndLimitsParallelExports() {
        var first=start("lease-key","card_A","card_B");
        var obsolete=tx.execute(t->exportStore.claim(Instant.now(),Instant.now().minusSeconds(1))).orElseThrow();
        var single=start("parallel-key","card_A");
        assertThatThrownBy(()->start("over-capacity","card_B")).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("RATE_LIMITED");
        assertThat(worker.runOnce()).isTrue();
        assertThat(exports.get(first.id(),"usr_export").status()).isEqualTo("completed");
        assertThat(Boolean.TRUE.equals(tx.execute(t->exportStore.fail(obsolete,Instant.now())))).isFalse();
        worker.runOnce();assertThat(exports.get(single.id(),"usr_export").status()).isEqualTo("completed");
    }
    @Test void projectDeletionDuringUploadKeepsReceiptUntilCleanupCanDeleteTheFile() throws Exception {
        var value=start("delete-during-upload","card_A","card_B");
        String key="exports/"+value.id()+"/test-upload";
        tx.executeWithoutResult(t->exportStore.reserveFile(key,value.id(),"asset_exportUpload",Instant.now()));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var uploading=executor.submit(()->tx.executeWithoutResult(t->{
                assertThat(exportStore.lockReservedFile(key)).isTrue();
                locked.countDown();
                try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                storage.put(key,current,"application/zip");
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                mvc.perform(delete("/api/v1/projects/prj_export").header("Authorization",authorization)).andExpect(status().isNoContent());
                worker.cleanup();
                assertThat(jdbc.queryForObject("select count(*) from export_files where storage_key=?",Integer.class,key)).isEqualTo(1);
            } finally {release.countDown();}
            uploading.get(10,TimeUnit.SECONDS);
        }
        assertThat(storage.data).containsKey(key);
        worker.cleanup();
        assertThat(storage.data).doesNotContainKey(key);
        assertThat(Boolean.TRUE.equals(tx.execute(t->exportStore.lockReservedFile(key)))).isFalse();
    }
}
