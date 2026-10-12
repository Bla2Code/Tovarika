package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.shared.application.ApiFailure;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false", "tovarika.security.cors.allowed-origins=https://ui.test"
})
@org.springframework.context.annotation.Import(CardSeriesStorageConfiguration.class)
class CardSeriesIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired CardGenerationService cards;
    @Autowired CardGenerationWorker worker;
    @Autowired CardSeriesStorageConfiguration.MemoryStorage storage;
    @Autowired CardGenerationStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper mapper;
    @Autowired AuthenticationStore auth;
    @Autowired SessionService sessions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String token;
    String originalSeries;
    String originalVariantRecipe;
    String originalVariantIdea;
    final String path = "/api/v1/projects/prj_series/cards";

    @BeforeEach void setup() {
        storage.data.clear();storage.failReads=false;
        jdbc.execute("truncate users,trial_sessions,products,projects,product_analyses,project_jobs,cards cascade");
        jdbc.update("delete from assets where purpose='source_image'");
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_series','series@example.test',true,'ACTIVE',now(),now())");
        jdbc.update("insert into assets(id,purpose,media_type,size_bytes,url,storage_key,created_at) values('asset_source','source_image','image/png',100,'','products/test-source',now())");
        jdbc.update("insert into products(id,name,owner_user_id,status,source_asset_id,created_at,updated_at) values('prd_series','Кружка','usr_series','analysis_ready','asset_source',now(),now())");
        jdbc.update("insert into product_analyses(id,product_id,description,idea,generation_prompt,revision,created_at,updated_at) values('analysis_series','prd_series','Белая кружка с видимой ручкой.','analysis idea unused','analysis prompt unused',1,now(),now())");
        jdbc.update("insert into projects(id,product_id,name,selected_template_id,default_aspect_ratio,created_at,updated_at) values('prj_series','prd_series','Series','tpl_fashion_hero','3:4',now(),now())");
        token=sessions.create(auth.findUserById("usr_series").orElseThrow(),new RequestMetadata("Test Browser","192.0.2.xxx")).accessToken();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        originalVariantRecipe=jdbc.queryForObject("select generation_recipe::text from template_card_variants where id='tpl_fashion_hero_v02'",String.class);
        originalVariantIdea=jdbc.queryForObject("select default_idea from template_card_variants where id='tpl_fashion_hero_v02'",String.class);
        originalSeries=jdbc.queryForObject("select recipe_json::text from templates where id='tpl_fashion_hero'",String.class);
    }
    @AfterEach void restoreCatalog() {
        jdbc.update("update template_card_variants set generation_recipe=cast(? as jsonb),default_idea=? where id='tpl_fashion_hero_v02'",originalVariantRecipe,originalVariantIdea);
        jdbc.update("update templates set recipe_json=cast(? as jsonb) where id='tpl_fashion_hero'",originalSeries);
        jdbc.execute("truncate projects,cards,project_jobs cascade");
        jdbc.update("delete from templates where id='tpl_series_other'");
    }

    @Test void draftIsReadOnlyAndHttpIdeaReachesCompilerAndCard() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from template_card_variants where template_id='tpl_fashion_hero'",Integer.class)).isEqualTo(10);
        mvc.perform(get(path+"/next-draft").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.position").value(1)).andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.supportedAspectRatios.length()").value(4))
                .andExpect(jsonPath("$.variantId").value("tpl_fashion_hero_v01"));
        assertThat(count()).isZero();
        var first=start(1,"first-key"); finish(first);
        mvc.perform(get(path+"/next-draft").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.position").value(2)).andExpect(jsonPath("$.variantId").value("tpl_fashion_hero_v02"));
        var response=mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key","second-key")
                .contentType("application/json").content("{\"templateId\":\"tpl_fashion_hero\",\"variantId\":\"tpl_fashion_hero_v02\",\"idea\":\"Товар справа, акцент на ручке\",\"aspectRatio\":\"16:9\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String id=mapper.readTree(response.getContentAsString()).path("target").path("id").asText();
        assertThat(jdbc.queryForObject("select prompt from cards where id=?",String.class,id))
                .contains("Товар справа, акцент на ручке","Mandatory factual condition","Replace the reference arrangement")
                .doesNotContain("analysis idea unused","analysis prompt unused","relative placement");
        mvc.perform(get(path+"/"+id).header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.idea").value("Товар справа, акцент на ручке"))
                .andExpect(jsonPath("$.variantId").value("tpl_fashion_hero_v02"))
                .andExpect(jsonPath("$.prompt").doesNotExist()).andExpect(jsonPath("$.recipe").doesNotExist());
        assertThat(count()).isEqualTo(2);
    }

    @Test void ownershipConflictValidationAndMissingScenario() throws Exception {
        mvc.perform(get(path+"/next-draft")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/prj_other/cards/next-draft").header("Authorization","Bearer "+token))
                .andExpect(status().isNotFound());
        assertCode(() -> cards.start("prj_series","bad-variant", "usr_series",null,"tpl_fashion_hero",null,"3:4","foreign",null),"CARD_VARIANT_INVALID");
        assertCode(() -> cards.start("prj_series","blank-idea", "usr_series",null,"tpl_fashion_hero",null,"3:4","tpl_fashion_hero_v01","  "),"VALIDATION_ERROR");
        assertCode(() -> cards.start("prj_series","mixed-source", "usr_series",null,"tpl_fashion_hero","technical prompt","3:4",null,null),"VALIDATION_ERROR");
        var first=start(1,"first-key");
        assertThat(start(1,"first-key").id()).isEqualTo(first.id());
        assertCode(() -> start(2,"busy-key"),"CARD_BUSY");
        finish(first);
        assertCode(() -> start(1,"stale-key"),"CARD_DRAFT_STALE");
        jdbc.update("insert into templates(id,name,recipe_json) values('tpl_series_other','Other',cast(? as jsonb))",originalSeries);
        var draft=cards.nextDraft("prj_series","tpl_series_other","usr_series",null);
        assertThat(draft.state()).isEqualTo("variant_unavailable");
        assertThat(count()).isEqualTo(1);
        assertCode(() -> cards.start("prj_series","missing-variant","usr_series",null,"tpl_series_other",null,"3:4",null,null),"CARD_VARIANT_UNAVAILABLE");
    }

    @Test void draftDoesNotRequireAnalysisOrSourceAndCannotStartWithoutThem() {
        jdbc.update("update products set source_asset_id=null,status='uploaded' where id='prd_series'");
        assertThat(cards.nextDraft("prj_series",null,"usr_series",null).state()).isEqualTo("ready");
        assertThat(count()).isZero();
        assertCode(() -> start(1,"not-ready-key"),"RESULT_NOT_READY");
    }

    @Test void snapshotSurvivesCatalogAndAnalysisChangeAndStyleSwitchUsesCurrentPosition() {
        var first=start(1,"first-key"); finish(first);
        String saved=jdbc.queryForObject("select card_series_snapshot::text from projects",String.class);
        jdbc.update("update templates set recipe_json='{\"schemaVersion\":1,\"style\":\"changed catalog style\"}'::jsonb where id='tpl_fashion_hero'");
        var second=start(2,"second-key");
        String prompt=jdbc.queryForObject("select prompt from cards where id=?",String.class,second.cardId());
        jdbc.update("update template_card_variants set default_idea='changed catalog idea',generation_recipe=jsonb_set(generation_recipe,'{composition}','\"changed catalog composition\"'::jsonb) where id='tpl_fashion_hero_v02'");
        jdbc.update("update product_analyses set description='Изменённое описание',revision=2");
        assertThat(transactions.execute(tx->store.source(second)).prompt()).isEqualTo(prompt).doesNotContain("changed catalog style","Изменённое описание","changed catalog idea","changed catalog composition");
        assertThat(jdbc.queryForObject("select analysis_description_snapshot from cards where id=?",String.class,second.cardId())).contains("Белая кружка");
        assertThat(jdbc.queryForObject("select card_series_snapshot::text from projects",String.class)).isEqualTo(saved);
        finish(second);
        jdbc.update("insert into templates(id,name,recipe_json) values('tpl_series_other','Other',cast(? as jsonb))",originalSeries);
        jdbc.update("insert into template_card_variants(id,template_id,position,title,default_idea,generation_recipe) select 'other_v03','tpl_series_other',3,title,default_idea,generation_recipe from template_card_variants where id='tpl_fashion_hero_v03'");
        assertThat(cards.nextDraft("prj_series","tpl_series_other","usr_series",null).variantId()).isEqualTo("other_v03");
        assertThat(jdbc.queryForObject("select selected_template_id from projects",String.class)).isEqualTo("tpl_fashion_hero");
        var third=cards.start("prj_series","third-key","usr_series",null,"tpl_series_other",null,"1:1","other_v03",null);
        assertThat(cards.getCard("prj_series",third.cardId(),"usr_series",null).position()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select selected_template_id from projects",String.class)).isEqualTo("tpl_series_other");
    }

    @Test void tenPositionsReplayAndFailedRetryDoNotCreateEleventhCard() {
        CardGenerationJob first=null, last=null;
        for(int i=1;i<=10;i++) { last=start(i,"series-key-"+i); if(i==1) first=last; if(i<10) finish(last); }
        assertThat(start(1,"series-key-1").id()).isEqualTo(first.id());
        transactions.executeWithoutResult(tx->{var claimed=store.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow();store.fail(claimed,Instant.now());});
        assertThat(cards.nextDraft("prj_series",null,"usr_series",null).state()).isEqualTo("limit_reached");
        assertCode(() -> start(10,"eleventh-key"),"CARD_LIMIT_EXCEEDED");
        var retry=cards.retry("prj_series",last.cardId(),"retry-key","usr_series",null);
        assertThat(retry.cardId()).isEqualTo(last.cardId());
        assertThat(retry.id()).isNotEqualTo(last.id());
        assertThat(cards.retry("prj_series",last.cardId(),"retry-key","usr_series",null).id()).isEqualTo(retry.id());
        assertThat(count()).isEqualTo(10);
        finish(retry);
        String cardId=last.cardId();
        assertCode(() -> cards.retry("prj_series",cardId,"ready-retry","usr_series",null),"CARD_RETRY_NOT_ALLOWED");
    }

    @Test void twoConcurrentStartsReserveOnlyOnePosition() throws Exception {
        var latch=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<String>> results=List.of(executor.submit(()->race(latch,"race-key-a")),executor.submit(()->race(latch,"race-key-b")));
            latch.countDown();
            assertThat(List.of(results.get(0).get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("queued","CARD_BUSY");
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test void trialDraftAndRetryUseOwnershipAndStrictMutationOrigin() throws Exception {
        var cookie=mvc.perform(post("/api/v1/trial-session").header("Origin","https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("__Host-tovarika_trial");
        String trial=jdbc.queryForObject("select id from trial_sessions",String.class);
        jdbc.update("update products set owner_user_id=null,owner_trial_session_id=? where id='prd_series'",trial);
        mvc.perform(get(path+"/next-draft").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get(path+"/next-draft").cookie(cookie).header("Authorization","Bearer "+token))
                .andExpect(status().isNotFound());
        String body="{\"templateId\":\"tpl_fashion_hero\",\"variantId\":\"tpl_fashion_hero_v01\",\"aspectRatio\":\"3:4\"}";
        mvc.perform(post(path).cookie(cookie).header("Idempotency-Key","trial-card-key").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).header("Origin","https://unknown.test").header("Idempotency-Key","trial-card-key").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        var response=mvc.perform(post(path).cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","trial-card-key").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String cardId=mapper.readTree(response.getContentAsString()).path("target").path("id").asText();
        transactions.executeWithoutResult(tx->{var job=store.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow();store.fail(job,Instant.now());});
        mvc.perform(post(path+"/"+cardId+"/retry").cookie(cookie).header("Idempotency-Key","trial-retry-key"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path+"/"+cardId+"/retry").cookie(cookie).header("Origin","https://unknown.test").header("Idempotency-Key","trial-retry-key"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path+"/"+cardId+"/retry").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key","trial-retry-key"))
                .andExpect(status().isAccepted());
        assertThat(count()).isEqualTo(1);
        jdbc.update("update trial_sessions set expires_at=now()-interval '1 second'");
        mvc.perform(get(path+"/next-draft").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test void legacyFirstCardSuppliesStyleAndReferenceOnFirstContinuation() {
        var first=start(1,"first-key");finish(first);
        jdbc.update("update projects set card_series_snapshot=null");
        jdbc.update("update templates set recipe_json='{\"schemaVersion\":1,\"style\":\"new catalog\"}'::jsonb where id='tpl_fashion_hero'");
        assertThat(cards.nextDraft("prj_series",null,"usr_series",null).position()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select card_series_snapshot::text from projects",String.class)).isNull();
        var second=start(2,"second-key");
        assertThat(jdbc.queryForObject("select recipe_snapshot::text from cards where id=?",String.class,second.cardId()))
                .isEqualTo(originalSeries);
        assertThat(jdbc.queryForObject("select reference_asset_snapshot_id from cards where id=?",String.class,second.cardId()))
                .isEqualTo(jdbc.queryForObject("select reference_asset_snapshot_id from cards where id=?",String.class,first.cardId()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1:1,1024,1024", "3:4,1152,1536", "4:5,1024,1280", "16:9,1536,864"})
    void workerGeneratesSeriesWithAllRatiosAndRetriesFailedCard(String ratio,int width,int height) {
        var first=start(1,"worker-first");
        assertThat(worker.runOnce()).isTrue();
        assertThat(cards.getCard("prj_series",first.cardId(),"usr_series",null).status()).isEqualTo("ready");
        var next=cards.start("prj_series","worker-next","usr_series",null,"tpl_fashion_hero",null,ratio,"tpl_fashion_hero_v02","Мой акцент на ручке");
        String prompt=jdbc.queryForObject("select prompt from cards where id=?",String.class,next.cardId());
        storage.failReads=true;
        assertThat(worker.runOnce()).isTrue();
        assertThat(cards.getCard("prj_series",next.cardId(),"usr_series",null).status()).isEqualTo("error");
        var retry=cards.retry("prj_series",next.cardId(),"worker-retry","usr_series",null);
        storage.failReads=false;
        assertThat(worker.runOnce()).isTrue();
        var card=cards.getCard("prj_series",next.cardId(),"usr_series",null);
        assertThat(card.status()).isEqualTo("ready");assertThat(card.position()).isEqualTo(2);
        assertThat(card.image().width()).isEqualTo(width);assertThat(card.image().height()).isEqualTo(height);
        assertThat(jdbc.queryForObject("select prompt from cards where id=?",String.class,card.id())).isEqualTo(prompt);
        assertThat(cards.getJob(retry.id(),"usr_series",null).status()).isEqualTo("completed");
        assertThat(count()).isEqualTo(2);
    }

    private String race(CountDownLatch latch,String key) throws Exception {
        latch.await();try{return start(1,key).status();}catch(ApiFailure failure){return failure.code();}
    }
    private CardGenerationJob start(int pos,String key) {
        return cards.start("prj_series",key,"usr_series",null,"tpl_fashion_hero",null,"3:4",String.format("tpl_fashion_hero_v%02d",pos),null);
    }
    private void finish(CardGenerationJob job) {
        jdbc.update("update project_jobs set status='completed' where id=?",job.id());
        jdbc.update("update cards set status='ready' where id=?",job.cardId());
    }
    private int count(){return jdbc.queryForObject("select card_count from projects where id='prj_series'",Integer.class);}
    private void assertCode(ThrowingCallable operation,String code){assertThatThrownBy(operation).isInstanceOf(ApiFailure.class).extracting("code").isEqualTo(code);}
}
