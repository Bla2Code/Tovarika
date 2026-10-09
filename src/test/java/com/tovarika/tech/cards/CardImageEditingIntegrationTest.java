package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.cards.editing.application.*;
import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.shared.application.ApiFailure;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false", "tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.cards.worker-enabled=false", "tovarika.analysis.worker-enabled=false", "tovarika.ai.openai.mode=stub"
})
@org.springframework.context.annotation.Import(CardSeriesStorageConfiguration.class)
class CardImageEditingIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired CardGenerationService cards;
    @Autowired CardGenerationWorker generationWorker;
    @Autowired CardImageEditingService edits;
    @Autowired CardImageEditingWorker editWorker;
    @Autowired ImageEditingStore editStore;
    @Autowired CardSeriesStorageConfiguration.MemoryStorage storage;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired AuthenticationStore auth;
    @Autowired SessionService sessions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token,cardId,path;
    final ImageEdit entire=new ImageEdit("entire","Change the background",null,null,null,null);

    @BeforeEach void setup() {
        storage.data.clear();storage.failReads=false;
        jdbc.execute("truncate users,trial_sessions,products,projects,product_analyses,project_jobs,cards cascade");
        jdbc.update("delete from assets where id='asset_edit_source'");
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_edit','edit@example.test',true,'ACTIVE',now(),now())");
        jdbc.update("insert into assets(id,purpose,media_type,size_bytes,url,storage_key,created_at) values('asset_edit_source','source_image','image/png',100,'','products/edit-source',now())");
        jdbc.update("insert into products(id,name,owner_user_id,status,source_asset_id,created_at,updated_at) values('prd_edit','Mug','usr_edit','analysis_ready','asset_edit_source',now(),now())");
        jdbc.update("insert into product_analyses(id,product_id,description,idea,generation_prompt,revision,created_at,updated_at) values('analysis_edit','prd_edit','White mug.','idea','prompt',1,now(),now())");
        jdbc.update("insert into projects(id,product_id,name,selected_template_id,default_aspect_ratio,created_at,updated_at) values('prj_edit','prd_edit','Edit','tpl_fashion_hero','3:4',now(),now())");
        token=sessions.create(auth.findUserById("usr_edit").orElseThrow(),new RequestMetadata("Test Browser","192.0.2.xxx")).accessToken();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var job=cards.start("prj_edit","original-key","usr_edit",null,"tpl_fashion_hero",null,"3:4","tpl_fashion_hero_v01",null);
        cardId=job.cardId();path="/api/v1/projects/prj_edit/cards/"+cardId;
        assertThat(generationWorker.runOnce()).isTrue();
    }
    @AfterEach void cleanup(){storage.failReads=false;jdbc.execute("truncate projects,cards,project_jobs cascade");}

    @Test void threeEditsUndoAndRedoRestoreExactAssetsAcrossReloadAndKeepCountAndPreview() throws Exception {
        var original=current();String preview=jdbc.queryForObject("select preview_asset_id from projects where id='prj_edit'",String.class);
        var first=complete("edit-first-key",entire);
        var second=complete("edit-second-key",new ImageEdit("remove_background",null,null,"preserve_graphics",null,null));
        var third=complete("edit-third-key",new ImageEdit("resize",null,null,null,"1:1","fit_pad"));
        assertThat(third.aspectRatio()).isEqualTo("1:1");
        assertThat(third.previousVersionId()).isEqualTo(second.currentVersionId());
        assertThat(second.image().hasAlpha()).isTrue();
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.id").value(third.currentVersionId()))
                .andExpect(jsonPath("$.imageRevision").value(4)).andExpect(jsonPath("$.canUndo").value(true));
        String undoBody=guard(third);
        undo("undo-third-key",undoBody,second.currentVersionId(),5);
        undo("undo-third-key",undoBody,second.currentVersionId(),5);
        assertThat(current().image().id()).isEqualTo(second.image().id());
        undo("undo-second-key",guard(current()),first.currentVersionId(),6);
        undo("undo-first-key",guard(current()),original.currentVersionId(),7);
        assertThat(current().image().id()).isEqualTo(original.image().id());
        assertThat(current().aspectRatio()).isEqualTo(original.aspectRatio());
        assertThat(current().canUndo()).isFalse();
        mvc.perform(post(path+"/undo").header("Authorization","Bearer "+token).header("Idempotency-Key","undo-unavailable")
                .contentType("application/json").content(guard(current())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNDO_NOT_AVAILABLE"));
        assertThat(current().canRedo()).isTrue();
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.canRedo").value(true));
        mvc.perform(get("/api/v1/projects/prj_edit/cards").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].canRedo").value(true));
        String redoBody=guard(current());
        redo("redo-first-key",redoBody,first.currentVersionId(),8);
        redo("redo-first-key",redoBody,first.currentVersionId(),8);
        assertThat(current().image().id()).isEqualTo(first.image().id());
        redo("redo-second-key",guard(current()),second.currentVersionId(),9);
        assertThat(current().image().id()).isEqualTo(second.image().id());
        assertThat(current().image().hasAlpha()).isTrue();
        redo("redo-third-key",guard(current()),third.currentVersionId(),10);
        assertThat(current().image().id()).isEqualTo(third.image().id());
        assertThat(current().aspectRatio()).isEqualTo("1:1");
        assertThat(current().canRedo()).isFalse();
        // An older receipt returns the current Card, without undoing later navigation.
        redo("redo-first-key",redoBody,third.currentVersionId(),10);
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","redo-unavailable")
                .contentType("application/json").content(guard(current())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REDO_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from cards",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select card_count from projects where id='prj_edit'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select preview_asset_id from projects where id='prj_edit'",String.class)).isEqualTo(preview);
        assertThat(jdbc.queryForObject("select count(*) from card_image_versions",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from project_jobs",Integer.class)).isEqualTo(4);
    }

    private static final String ERASE="{\"kind\":\"erase\",\"mask\":{\"kind\":\"brush\",\"strokes\":[{\"radius\":0.06,\"points\":[{\"x\":0.5,\"y\":0.4},{\"x\":0.6,\"y\":0.6}]}]}}";

    @Test void erasePersistsMaskAndRestoresExactAssetsWithUndoRedoAndBranching() throws Exception {
        var original=current();String body=request(original,ERASE);
        var accepted=postEdit("erase-http-key",body,202);
        String job=accepted.path("jobId").asText();
        var persisted=mapper.readValue(jdbc.queryForObject("select image_edit_payload::text from project_jobs where id=?",String.class,job),ImageEdit.class);
        assertThat(persisted.mask().strokes().getFirst().points()).hasSize(2);
        assertThat(postEdit("erase-http-key",body.replace("0.06","0.07"),409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(editWorker.runOnce()).isTrue();
        var erased=current();
        assertThat(edits.findJob(job,"usr_edit",null).orElseThrow().status()).isEqualTo("completed");
        assertThat(erased.image().id()).isNotEqualTo(original.image().id());
        assertThat(erased.image().mediaType()).isEqualTo("image/png");
        assertThat(erased.aspectRatio()).isEqualTo(original.aspectRatio());
        assertThat(postEdit("erase-http-key",body,202).path("jobId").asText()).isEqualTo(job);
        undo("erase-undo-key",guard(current()),original.currentVersionId(),3);
        assertThat(current().image().id()).isEqualTo(original.image().id());
        redo("erase-redo-key",guard(current()),erased.currentVersionId(),4);
        assertThat(current().image().id()).isEqualTo(erased.image().id());
        undo("erase-undo-again",guard(current()),original.currentVersionId(),5);
        postEdit("erase-new-branch",request(current(),ERASE.replace("0.06","0.08")),202);
        assertThat(editWorker.runOnce()).isTrue();
        assertThat(current().canRedo()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from cards",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from card_image_versions where card_id=?",Integer.class,cardId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from assets where id=?",Integer.class,erased.image().id())).isEqualTo(1);
    }

    @Test void eraseRejectsMalformedMasksLimitsAndOversizedBodiesBeforeEnqueue() throws Exception {
        var source=current();int count=0;
        for(String invalid:java.util.List.of(
            "{\"kind\":\"erase\"}",
            ERASE.replace("\"brush\"","\"rectangle\""),
            ERASE.replace("0.06","0"), ERASE.replace("0.06","0.26"),
            ERASE.replace("\"x\":0.5","\"x\":1.1"),
            ERASE.replace("\"y\":0.4","\"y\":-0.1"),
            ERASE.replace("\"radius\":0.06","\"radius\":\"0.06\""),
            ERASE.replace("\"radius\":0.06","\"radius\":0.06,\"url\":\"https://unused.test/mask\""),
            "{\"kind\":\"erase\",\"mask\":{\"kind\":\"brush\",\"strokes\":[]}}")) {
            var response=mvc.perform(post(path+"/image-edits").header("Authorization","Bearer "+token)
                .header("Idempotency-Key","erase-invalid-"+(count++)).contentType("application/json").content(request(source,invalid))).andReturn().getResponse();
            assertThat(response.getStatus()).isIn(400,422);
            assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        String point="{\"x\":0.5,\"y\":0.5}";
        String stroke="{\"radius\":0.06,\"points\":["+String.join(",",java.util.Collections.nCopies(1024,point))+"]}";
        String excessive="{\"kind\":\"erase\",\"mask\":{\"kind\":\"brush\",\"strokes\":["+String.join(",",java.util.Collections.nCopies(9,stroke))+"]}}";
        postEdit("erase-total-limit",request(source,excessive),422);
        excessive=excessive.replace(String.join(",",java.util.Collections.nCopies(9,stroke)),String.join(",",java.util.Collections.nCopies(65,"{\"radius\":0.06,\"points\":["+point+"]}")));
        postEdit("erase-stroke-limit",request(source,excessive),422);
        postEdit("erase-byte-limit",request(source,ERASE)+" ".repeat(1048576),400);
        assertThat(jdbc.queryForObject("select count(*) from project_jobs",Integer.class)).isEqualTo(1);
        assertThat(current().imageRevision()).isEqualTo(1);
    }

    @Test void oldSixFieldPayloadStillReplaysAndQueuedJobDeserializesAfterMaskUpgrade() throws Exception {
        var base=current();String id=start("legacy-payload-key",entire);
        String oldPayload="{\"kind\":\"entire\",\"prompt\":\"Change the background\",\"rect\":null,\"foreground\":null,\"aspectRatio\":null,\"mode\":null}";
        String canonical=mapper.writeValueAsString(java.util.List.of("image-edit","prj_edit",cardId,base.currentVersionId(),base.imageRevision(),oldPayload));
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("select command_digest from project_jobs where id=?",String.class,id)).isEqualTo(digest);
        assertThat(mapper.readTree(jdbc.queryForObject("select image_edit_payload::text from project_jobs where id=?",String.class,id)).has("mask")).isFalse();
        jdbc.update("update project_jobs set image_edit_payload=?::jsonb,command_digest=? where id=?",oldPayload,digest,id);
        assertThat(edits.start("prj_edit",cardId,"legacy-payload-key","usr_edit",null,base.currentVersionId(),base.imageRevision(),entire,false)).isEqualTo(id);
        assertThat(editWorker.runOnce()).isTrue();
        assertThat(edits.findJob(id,"usr_edit",null).orElseThrow().status()).isEqualTo("completed");
    }

    @Test void httpEditIdempotencyBusyAndAbaGuard() throws Exception {
        var original=current();String body=request(original,"{\"kind\":\"entire\",\"prompt\":\"Blue background\"}");
        var accepted=postEdit("http-edit-key",body,202);
        postEdit("http-edit-key",body,202);
        assertThat(current().canUndo()).isFalse();
        postEdit("different-key",body,409);
        assertThat(postEdit("http-edit-key",body.replace("Blue","Red"),409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(editWorker.runOnce()).isTrue();
        assertThat(postEdit("http-edit-key",body,202).path("jobId").asText()).isEqualTo(accepted.path("jobId").asText());
        mvc.perform(get("/api/v1/jobs/"+accepted.path("jobId").asText()).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.type").value("card_image_edit"))
                .andExpect(jsonPath("$.result.versionId").value(current().currentVersionId()));
        undo("http-undo-key",guard(current()),original.currentVersionId(),3);
        assertThat(postEdit("stale-aba-key",body,409).path("code").asText()).isEqualTo("CARD_VERSION_CONFLICT");
        assertThatThrownBy(()->cards.retry("prj_edit",cardId,"http-edit-key","usr_edit",null))
                .isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->edits.undo("prj_edit",cardId,"original-key","usr_edit",null,current().currentVersionId(),3))
                .isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test void failedEditKeepsSuccessfulImageAndNextEditCanProceed() {
        var original=current();String job=start("failed-edit-key",entire);
        storage.failReads=true;assertThat(editWorker.runOnce()).isTrue();storage.failReads=false;
        var failed=edits.findJob(job,"usr_edit",null).orElseThrow();
        assertThat(failed.status()).isEqualTo("failed");assertThat(failed.failureCode()).isEqualTo("GENERATION_FAILED");
        assertThat(current().currentVersionId()).isEqualTo(original.currentVersionId());
        assertThat(current().imageRevision()).isEqualTo(1);assertThat(current().status()).isEqualTo("ready");
        assertThat(current().canUndo()).isFalse();
        assertThat(complete("after-failure-key",entire).canUndo()).isTrue();
    }

    @Test void branchingClearsRedoOnlyOnSuccessfulCommitAndPreservesEveryOldVersion() throws Exception {
        var original=current();
        var first=complete("branch-first-key",entire);
        var second=complete("branch-second-key",entire);
        undo("branch-undo-second",guard(current()),first.currentVersionId(),4);
        undo("branch-undo-first",guard(current()),original.currentVersionId(),5);
        String failedJob=start("branch-failed-key",entire);
        assertThat(current().canRedo()).isFalse();
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","branch-busy-redo")
                .contentType("application/json").content(guard(current())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_BUSY"));
        storage.failReads=true;assertThat(editWorker.runOnce()).isTrue();storage.failReads=false;
        assertThat(edits.findJob(failedJob,"usr_edit",null).orElseThrow().status()).isEqualTo("failed");
        assertThat(current().canRedo()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from card_image_redo_stack where card_id=?",Integer.class,cardId)).isEqualTo(2);
        var branch=complete("branch-success-key",entire);
        assertThat(branch.previousVersionId()).isEqualTo(original.currentVersionId());
        assertThat(branch.canRedo()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from card_image_versions where card_id=?",Integer.class,cardId)).isEqualTo(4);
        assertThat(jdbc.queryForList("select image_asset_id from card_image_versions where card_id=?",String.class,cardId))
                .contains(first.image().id(),second.image().id());
        assertThat(jdbc.queryForObject("select count(*) from assets where id in (?,?)",Integer.class,first.image().id(),second.image().id())).isEqualTo(2);
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","branch-empty-redo")
                .contentType("application/json").content(guard(current())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REDO_NOT_AVAILABLE"));
        undo("branch-undo-new",guard(current()),original.currentVersionId(),7);
        redo("branch-redo-new",guard(current()),branch.currentVersionId(),8);
        assertThat(current().canRedo()).isFalse();
    }

    @Test void redoGuardsCrossCommandIdempotencyAndReplayBeforeBusy() throws Exception {
        var original=current();var edited=complete("redo-edit-key",entire);
        String undoBody=guard(edited);
        undo("redo-undo-key",undoBody,original.currentVersionId(),3);
        for(String key:java.util.List.of("redo-undo-key","redo-edit-key","original-key")) {
            mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                    .contentType("application/json").content(guard(current())))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","redo-aba-key")
                .contentType("application/json").content(guard(original)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_VERSION_CONFLICT"));
        String redoBody=guard(current());
        redo("redo-replay-key",redoBody,edited.currentVersionId(),4);
        mvc.perform(post(path+"/undo").header("Authorization","Bearer "+token).header("Idempotency-Key","redo-replay-key")
                .contentType("application/json").content(redoBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(()->cards.retry("prj_edit",cardId,"redo-replay-key","usr_edit",null))
                .isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(postEdit("redo-replay-key",request(current(),"{\"kind\":\"entire\",\"prompt\":\"Change\"}"),409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        start("redo-pending-key",entire);
        redo("redo-replay-key",redoBody,edited.currentVersionId(),4);
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","redo-replay-key")
                .contentType("application/json").content(guard(original)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void concurrentRedoUsesRevisionFencingAndCannotConsumeTwoSteps() throws Exception {
        var original=current();var first=complete("race-first-key",entire);complete("race-second-key",entire);
        undo("race-undo-second",guard(current()),first.currentVersionId(),4);
        undo("race-undo-first",guard(current()),original.currentVersionId(),5);
        var source=current();var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->redoRace(latch,"redo-race-a",source));
            var b=pool.submit(()->redoRace(latch,"redo-race-b",source));latch.countDown();
            assertThat(java.util.List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("completed","CARD_VERSION_CONFLICT");
        }
        assertThat(current().currentVersionId()).isEqualTo(first.currentVersionId());
        assertThat(current().imageRevision()).isEqualTo(6);
        assertThat(current().canRedo()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from card_image_redo_stack where card_id=?",Integer.class,cardId)).isEqualTo(1);
    }
    private String redoRace(CountDownLatch latch,String key,CardView source) throws Exception {
        latch.await();try{edits.redo("prj_edit",cardId,key,"usr_edit",null,source.currentVersionId(),source.imageRevision());return "completed";}
        catch(ApiFailure failure){return failure.code();}
    }

    @Test void staleCompletionAndExpiredAttemptCannotReplaceCurrentImage() {
        var original=current();String id=start("stale-worker-key",entire);
        var claimed=tx.execute(t->editStore.claim(Instant.now()).orElseThrow());
        jdbc.update("update cards set image_revision=image_revision+1 where id=?",cardId);
        assertThat(tx.<Boolean>execute(t->editStore.complete(claimed,new ImageEditingStore.Output("asset_stale","cards/stale",10,8,6,false),Instant.now()))).isFalse();
        assertThat(current().currentVersionId()).isEqualTo(original.currentVersionId());
        assertThat(edits.findJob(id,"usr_edit",null).orElseThrow().failureCode()).isEqualTo("CARD_VERSION_CONFLICT");
        start("expired-attempt-key",entire);
        var old=tx.execute(t->editStore.claim(Instant.now()).orElseThrow());
        jdbc.update("update project_jobs set lease_until=now()-interval '1 second' where id=?",old.id());
        var recovered=tx.execute(t->editStore.claim(Instant.now()).orElseThrow());
        assertThat(recovered.attempt()).isEqualTo(old.attempt()+1);
        assertThat(tx.<Boolean>execute(t->editStore.fail(old,"GENERATION_FAILED",Instant.now()))).isFalse();
        assertThat(tx.<Boolean>execute(t->editStore.complete(old,new ImageEditingStore.Output("asset_old","cards/old",10,8,6,false),Instant.now()))).isFalse();
        tx.executeWithoutResult(t->editStore.fail(recovered,"GENERATION_FAILED",Instant.now()));
    }

    @Test void strictValidationOwnershipLegacyRectangleAndTrialOrigin() throws Exception {
        var original=current();
        String entire=request(original,"{\"kind\":\"entire\",\"prompt\":\"Change\"}");
        mvc.perform(post(path+"/image-edits").header("Idempotency-Key","unauthenticated").contentType("application/json").content(entire)).andExpect(status().isUnauthorized());
        mvc.perform(post(path.replace("prj_edit","prj_missing")+"/image-edits").header("Authorization","Bearer "+token)
                .header("Idempotency-Key","missing-project").contentType("application/json").content(entire)).andExpect(status().isNotFound());
        postEdit("invalid-extra-key",entire.replace("\"prompt\":","\"extra\":1,\"prompt\":"),422);
        postEdit("invalid-space-key",entire.replace("Change","  "),400);
        postEdit("invalid-resize-key",request(original,"{\"kind\":\"resize\",\"aspectRatio\":\"1:1\"}"),422);
        postEdit("invalid-region-key",request(original,"{\"kind\":\"region\",\"prompt\":\"Change\",\"region\":{\"kind\":\"rectangle\",\"rect\":{\"x\":0.9,\"y\":0,\"width\":0.2,\"height\":1}}}"),422);
        mvc.perform(post(path+"/region-edits").header("Authorization","Bearer "+token).header("Idempotency-Key","legacy-region-key")
                .contentType("application/json").content("{\"prompt\":\"Change\",\"region\":{\"kind\":\"rectangle\",\"rect\":{\"x\":0,\"y\":0,\"width\":0.5,\"height\":0.5}}}"))
                .andExpect(status().isAccepted());
        assertThat(editWorker.runOnce()).isTrue();
        var cookie=mvc.perform(post("/api/v1/trial-session").header("Origin","https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("__Host-tovarika_trial");
        String trial=jdbc.queryForObject("select id from trial_sessions",String.class);
        jdbc.update("update products set owner_user_id=null,owner_trial_session_id=? where id='prd_edit'",trial);
        String undo=guard(cards.getCard("prj_edit",cardId,null,trial));
        mvc.perform(post(path+"/undo").cookie(cookie).header("Idempotency-Key","trial-undo-key")
                .contentType("application/json").content(undo)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/undo").cookie(cookie).header("Origin","https://unknown.test").header("Idempotency-Key","trial-undo-key")
                .contentType("application/json").content(undo)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/undo").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","trial-undo-key")
                .contentType("application/json").content(undo)).andExpect(status().isOk());
        String redo=guard(cards.getCard("prj_edit",cardId,null,trial));
        mvc.perform(post(path+"/redo").header("Idempotency-Key","redo-no-identity").contentType("application/json").content(redo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key","redo-other-owner")
                .contentType("application/json").content(redo)).andExpect(status().isNotFound());
        mvc.perform(post(path.replace("prj_edit","prj_missing")+"/redo").cookie(cookie).header("Origin","https://ui.test")
                .header("Idempotency-Key","redo-missing-project").contentType("application/json").content(redo)).andExpect(status().isNotFound());
        for(String origin:java.util.List.of("","https://unknown.test")) {
            var request=post(path+"/redo").cookie(cookie).header("Idempotency-Key","trial-redo-key").contentType("application/json").content(redo);
            if(!origin.isEmpty()) request.header("Origin",origin);
            mvc.perform(request).andExpect(status().isForbidden());
        }
        mvc.perform(post(path+"/redo").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","redo-extra-key")
                .contentType("application/json").content(redo.replace("}",",\"extra\":1}")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(path+"/redo").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","trial-redo-key")
                .contentType("application/json").content(redo)).andExpect(status().isOk()).andExpect(jsonPath("$.canRedo").value(false));
        String trialBody=request(cards.getCard("prj_edit",cardId,null,trial),"{\"kind\":\"entire\",\"prompt\":\"Change\"}");
        mvc.perform(post(path+"/image-edits").cookie(cookie).header("Idempotency-Key","trial-edit-key").contentType("application/json").content(trialBody)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/image-edits").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","trial-edit-key").contentType("application/json").content(trialBody)).andExpect(status().isAccepted());
    }

    @Test void simultaneousCommandsAllowOnlyOnePendingEdit() throws Exception {
        var original=current();var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->race(latch,"parallel-a-key",original));
            var b=pool.submit(()->race(latch,"parallel-b-key",original));latch.countDown();
            assertThat(java.util.List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("queued","CARD_BUSY");
        }
    }
    private String race(CountDownLatch latch,String key,CardView source) throws Exception {
        latch.await();try{edits.start("prj_edit",cardId,key,"usr_edit",null,source.currentVersionId(),source.imageRevision(),entire,false);return "queued";}
        catch(ApiFailure failure){return failure.code();}
    }
    private CardView current(){return cards.getCard("prj_edit",cardId,"usr_edit",null);}
    private String start(String key,ImageEdit edit){var c=current();return edits.start("prj_edit",cardId,key,"usr_edit",null,c.currentVersionId(),c.imageRevision(),edit,false);}
    private CardView complete(String key,ImageEdit edit){start(key,edit);assertThat(editWorker.runOnce()).isTrue();return current();}
    private String guard(CardView c){return "{\"baseVersionId\":\""+c.currentVersionId()+"\",\"expectedImageRevision\":"+c.imageRevision()+"}";}
    private String request(CardView c,String op){String g=guard(c);return g.substring(0,g.length()-1)+",\"operation\":"+op+"}";}
    private JsonNode postEdit(String key,String body,int status) throws Exception {
        var response=mvc.perform(post(path+"/image-edits").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType("application/json").content(body)).andExpect(status().is(status)).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString());
    }
    private void redo(String key,String body,String expected,long revision) throws Exception {
        mvc.perform(post(path+"/redo").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.id").value(expected)).andExpect(jsonPath("$.imageRevision").value(revision));
    }
    private void undo(String key,String body,String expected,long revision) throws Exception {
        mvc.perform(post(path+"/undo").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.id").value(expected)).andExpect(jsonPath("$.imageRevision").value(revision));
    }
}
