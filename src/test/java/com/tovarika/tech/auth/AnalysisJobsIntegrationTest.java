package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.analyses.application.*;
import com.tovarika.tech.analyses.domain.*;
import com.tovarika.tech.auth.application.*;
import com.tovarika.tech.images.application.ImageGenerationService;
import com.tovarika.tech.cards.CardGenerationWorker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.docker.compose.enabled=false","tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false","tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.analysis.worker-enabled=false"
})
@Import({AuthenticationContractIntegrationTest.TestDoubles.class,ProductUploadIntegrationTest.StorageConfig.class,
        AnalysisJobsIntegrationTest.ProviderConfig.class})
class AnalysisJobsIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisWorker worker;
    @Autowired AnalysisStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired FakeProvider provider;
    @Autowired EmailAuthenticationService auth;
    @Autowired MeterRegistry metrics;
    @Autowired ImageGenerationService imageGeneration;
    @Autowired CardGenerationWorker cardWorker;
    @Autowired ProductUploadIntegrationTest.FakeStorage storage;
    MockMvc mvc;
    Cookie cookie;
    @BeforeEach void setup() throws Exception {
        jdbc.execute("truncate users,trial_sessions,assets,products,authentication_rate_limits,product_uploads cascade");
        provider.calls.set(0);provider.fail=false;provider.entered=null;provider.release=null;
        storage.data.clear();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cookie=mvc.perform(post("/api/v1/trial-session").header("Origin","https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("__Host-tovarika_trial");
    }
    @Test void queuedProcessingCompletedAndProductRecovery() throws Exception {
        String product=product();
        String job=start(product,"operation-1");
        mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued")).andExpect(jsonPath("$.progress").value(0));
        assertProduct(product,"analysis_pending",job);
        provider.entered=new CountDownLatch(1);provider.release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(worker::runOnce);
            assertThat(provider.entered.await(10,TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("processing")).andExpect(jsonPath("$.startedAt").isNotEmpty());
            assertThat(start(product,"operation-1")).isEqualTo(job);
            assertThat(provider.calls.get()).isEqualTo(1);
            provider.release.countDown();
            assertThat(running.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertProduct(product,"analysis_ready",job);
        mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed")).andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        assertThat(worker.runOnce()).isFalse();
        assertThat(jdbc.queryForObject("select revision from product_analyses",Integer.class)).isEqualTo(1);
        assertThat(metrics.get("tovarika.analysis.operations").tag("outcome","completed").counter().count()).isGreaterThanOrEqualTo(1);
    }
    @Test void providerFailureIsSanitizedAndTerminal() throws Exception {
        String product=product();String job=start(product,"failure-key");provider.fail=true;
        worker.runOnce();
        var response=mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure.code").value("ANALYSIS_FAILED")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret-provider-key","private prompt");
        assertProduct(product,"analysis_failed",job);
        assertThatThrownBy(()->jdbc.update("update analysis_jobs set status='queued',finished_at=null where id=?",job))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(worker.runOnce()).isFalse();
        assertThat(start(product,"failure-key")).isEqualTo(job);
        provider.fail=false;
        String retry=start(product,"retry-operation");worker.runOnce();assertProduct(product,"analysis_ready",retry);
    }
    @Test void idempotencyConflictsAndOldReplayCannotOverwriteNewJob() throws Exception {
        String product=product(), other=product();String job=start(product,"same-key-123");
        mvc.perform(command(product,"another-key")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_ALREADY_RUNNING"));
        mvc.perform(command(other,"same-key-123")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        worker.runOnce();
        String next=start(product,"next-job-key");
        assertThat(start(product,"same-key-123")).isEqualTo(job);
        assertProduct(product,"analysis_pending",next);
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentIdenticalCommandsCreateOneOperation() throws Exception {
        String product=product();CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> call=()->{start.await(10,TimeUnit.SECONDS);return start(product,"parallel-key");};
            var first=executor.submit(call);var second=executor.submit(call);start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs",Integer.class)).isEqualTo(1);
        assertThat(provider.calls.get()).isZero();
    }
    @Test void crashedWorkerLeaseCanBeReclaimedAndStaleResultIsFenced() throws Exception {
        String product=product();String job=start(product,"recovery-key");
        var stale=transactions.execute(tx->store.claim(Instant.now(),Instant.now().minusSeconds(1))).orElseThrow();
        assertThat(stale.attempt()).isEqualTo(1);
        worker.runOnce();
        assertProduct(product,"analysis_ready",job);
        assertThat(provider.operationIds).contains(job);
        Boolean applied=transactions.execute(tx->store.complete(stale,new AnalysisResult("stale","stale","stale"),"stale",Instant.now()));
        assertThat(applied).isFalse();
        assertThat(jdbc.queryForObject("select description from product_analyses",String.class)).isEqualTo("Test description");
    }
    @Test void ownershipOriginAndMissingKeyAreEnforced() throws Exception {
        String product=product();String job=start(product,"owner-operation");
        mvc.perform(get("/api/v1/jobs/"+job)).andExpect(status().isUnauthorized());
        auth.register("other@example.com","correct horse battery staple",null,null);
        String bearer=auth.login("other@example.com","correct horse battery staple",new RequestMetadata("Test","127.0.0.xxx")).accessToken();
        mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie).header("Authorization","Bearer "+bearer)).andExpect(status().isNotFound());
        mvc.perform(command(product,"another-key").header("Authorization","Bearer "+bearer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/products/"+product+"/analysis").cookie(cookie).header("Idempotency-Key","valid-key"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products/"+product+"/analysis").cookie(cookie).header("Origin","https://ui.test"))
                .andExpect(status().isBadRequest());
    }

    @Test void analysisRateLimitAndIdempotentReplayAreStable() throws Exception {
        String product=product();
        for(int i=0;i<30;i++) {
            String key="rate-limit-key-"+i;
            String job=start(product,key);
            worker.runOnce();
            assertThat(start(product,key)).isEqualTo(job);
        }
        mvc.perform(command(product,"rate-limit-overflow"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs",Integer.class)).isEqualTo(30);
    }

    @Test void chatGptFactoryUsesOfflineStubForGenerationAndEditing() throws Exception {
        var request=new com.tovarika.tech.images.application.GenerationRequest(
                "A clean product card",
                java.util.List.of(new com.tovarika.tech.images.application.GenerationRequest.InputImage(
                        com.tovarika.tech.templates.TemplatePlaceholder.png(),"image/png","product")),64,48);
        var generated=imageGeneration.generate("chatgpt",request);
        assertThat(generated.stub()).isTrue();
        assertThat(generated.mediaType()).isEqualTo("image/png");
        assertThat(generated.width()).isEqualTo(64);
        var edited=imageGeneration.edit("chatgpt",fixture(),"image/png","Keep the product unchanged",48,64);
        assertThat(edited.stub()).isTrue();
        assertThatThrownBy(()->imageGeneration.generate("midjourney",request))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void firstCardUsesTemplateReferenceFlowAndKeepsPromptInternal() throws Exception {
        String product=product();
        start(product,"analysis-for-card");
        worker.runOnce();
        jdbc.update("""
                insert into templates(id,available,name,category_id,sort_order,recipe_json)
                values('tpl_test_card',true,'Test template','cat_universal',999,
                cast(? as jsonb))
                """, "{\"schemaVersion\":1,\"scene\":\"clean studio\",\"composition\":\"centered\",\"lighting\":\"soft\",\"style\":\"minimal\",\"palette\":[\"#FFFFFF\"],\"avoid\":[\"text\"]}");
        mvc.perform(get("/api/v1/templates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].isFavorite").value(false));
        mvc.perform(get("/api/v1/templates").param("favoriteOnly","true"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/templates/tpl_test_card/favorite").cookie(cookie)
                        .header("Origin","https://ui.test")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}"))
                .andExpect(status().isUnauthorized());
        var projectResponse=mvc.perform(post("/api/v1/projects").cookie(cookie).header("Origin","https://ui.test")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\""+product+"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String project=json.readTree(projectResponse.getContentAsString()).get("id").asString();
        var started=mvc.perform(post("/api/v1/projects/"+project+"/cards").cookie(cookie)
                        .header("Origin","https://ui.test").header("Idempotency-Key","first-card-key")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        // Previous UI builds send prompt and idea together with templateId.
                        .content("{\"templateId\":\"tpl_test_card\",\"aspectRatio\":\"4:5\","
                                + "\"prompt\":\"legacy prompt must be ignored\",\"idea\":\"legacy idea\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.target.type").value("card"))
                .andReturn().getResponse();
        var operation=json.readTree(started.getContentAsString());
        String job=operation.get("jobId").asString();
        String card=operation.get("target").get("id").asString();
        assertThat(cardWorker.runOnce()).isTrue();
        mvc.perform(get("/api/v1/jobs/"+job).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"));
        var cardResponse=mvc.perform(get("/api/v1/projects/"+project+"/cards/"+card).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.aspectRatio").value("4:5"))
                .andExpect(jsonPath("$.image.width").value(1024))
                .andExpect(jsonPath("$.image.height").value(1280)).andReturn().getResponse();
        assertThat(cardResponse.getContentAsString()).doesNotContain("prompt","idea","Test description");
        String internal=jdbc.queryForObject("select prompt from cards where id=?",String.class,card);
        assertThat(internal).contains("Test description")
                .doesNotContain("Test title","Test idea","legacy prompt","legacy idea");
    }

    @Test void generateCardRequiresExactlyTemplateOrPrompt() throws Exception {
        String product=product();start(product,"analysis-for-validation");worker.runOnce();
        var projectResponse=mvc.perform(post("/api/v1/projects").cookie(cookie).header("Origin","https://ui.test")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\""+product+"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String project=json.readTree(projectResponse.getContentAsString()).get("id").asString();
        mvc.perform(post("/api/v1/projects/"+project+"/cards").cookie(cookie)
                        .header("Origin","https://ui.test").header("Idempotency-Key","missing-source-key")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"aspectRatio\":\"3:4\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(String product,String key) {
        return post("/api/v1/products/"+product+"/analysis").cookie(cookie).header("Origin","https://ui.test").header("Idempotency-Key",key);
    }
    private String start(String product,String key) throws Exception {
        var response=mvc.perform(command(product,key)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued")).andExpect(jsonPath("$.target.type").value("product_analysis"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("jobId").asString();
    }
    private String product() throws Exception {
        byte[] image=fixture();
        var response=mvc.perform(multipart("/api/v1/products").file(new MockMultipartFile("image","source.png","image/png",image))
                .cookie(cookie).header("Origin","https://ui.test")).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asString();
    }
    private byte[] fixture() throws Exception {
        try(var input=getClass().getResourceAsStream("/images/product.png")){return input.readAllBytes();}
    }
    private void assertProduct(String id,String status,String job) throws Exception {
        mvc.perform(get("/api/v1/products/"+id).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.analysisJobId").value(job));
    }
    @TestConfiguration static class ProviderConfig {
        @Bean @Primary FakeProvider fakeProvider(){return new FakeProvider();}
    }
    static class FakeProvider implements AnalysisProvider {
        AtomicInteger calls=new AtomicInteger();
        Set<String> operationIds=ConcurrentHashMap.newKeySet();
        volatile boolean fail;
        volatile CountDownLatch entered,release;
        public AnalysisResult analyze(String id,byte[] image,String mediaType) {
            calls.incrementAndGet();operationIds.add(id);
            if(entered!=null)entered.countDown();
            if(release!=null)try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
            if(fail)throw new IllegalStateException("secret-provider-key private prompt");
            return new AnalysisResult("Test title","Test description","Test idea");
        }
    }
}
