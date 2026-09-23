package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.analyses.application.AnalysisWorker;
import com.tovarika.tech.auth.application.EmailAuthenticationService;
import com.tovarika.tech.auth.application.RequestMetadata;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.docker.compose.enabled=false","tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false","tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.analysis.worker-enabled=false"
})
@Import({AuthenticationContractIntegrationTest.TestDoubles.class,
        ProductUploadIntegrationTest.StorageConfig.class,AnalysisJobsIntegrationTest.ProviderConfig.class})
class AnalysisEditingIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisWorker worker;
    @Autowired EmailAuthenticationService auth;
    @Autowired AnalysisJobsIntegrationTest.FakeProvider provider;
    MockMvc mvc;
    Cookie trial;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("truncate users,trial_sessions,assets,products,authentication_rate_limits,product_uploads cascade");
        provider.calls.set(0);
        provider.fail=false;
        provider.entered=null;
        provider.release=null;
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        trial=mvc.perform(post("/api/v1/trial-session").header("Origin","https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("__Host-tovarika_trial");
    }

    @Test void readyAnalysisCanBeReadAndPartiallyUpdated() throws Exception {
        String product=readyProduct(trial,null,"ready-analysis");
        String asset=jdbc.queryForObject("select source_asset_id from products where id=?",String.class,product);
        String owner=jdbc.queryForObject("select owner_trial_session_id from products where id=?",String.class,product);
        var before=mvc.perform(get("/api/v1/products/"+product+"/analysis").cookie(trial))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.description").value("Test description"))
                .andExpect(jsonPath("$.generationPrompt").isNotEmpty()).andReturn().getResponse();
        String createdAt=json.readTree(before.getContentAsString()).get("createdAt").asString();
        String originalPrompt=json.readTree(before.getContentAsString()).get("generationPrompt").asString();

        var updated=mvc.perform(patch("/api/v1/products/"+product+"/analysis").cookie(trial)
                        .header("Origin","https://ui.test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"Minimal card with a lime accent\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.title").value("Test title"))
                .andExpect(jsonPath("$.description").value("Test description"))
                .andExpect(jsonPath("$.idea").value("Minimal card with a lime accent"))
                .andExpect(jsonPath("$.createdAt").value(createdAt)).andReturn().getResponse();
        assertThat(json.readTree(updated.getContentAsString()).get("generationPrompt").asString())
                .isNotEqualTo(originalPrompt).contains("Minimal card with a lime accent");
        assertThat(java.time.OffsetDateTime.parse(json.readTree(updated.getContentAsString()).get("updatedAt").asString()))
                .isAfter(java.time.OffsetDateTime.parse(createdAt));
        assertThat(jdbc.queryForObject("select source_asset_id from products where id=?",String.class,product)).isEqualTo(asset);
        assertThat(jdbc.queryForObject("select owner_trial_session_id from products where id=?",String.class,product)).isEqualTo(owner);
    }

    @Test void statesReturnStableErrors() throws Exception {
        String uploaded=product(trial,null);
        expectGet(uploaded,trial,404,"ANALYSIS_NOT_FOUND");
        start(uploaded,trial,null,"pending-state");
        expectGet(uploaded,trial,409,"RESULT_NOT_READY");
        jdbc.update("update analysis_jobs set status='processing',attempt=1,started_at=current_timestamp,lease_until=current_timestamp + interval '5 minutes' where product_id=?",uploaded);
        jdbc.update("update analysis_jobs set status='failed',finished_at=current_timestamp,lease_until=null where product_id=?",uploaded);
        jdbc.update("update products set status='analysis_failed' where id=?",uploaded);
        expectGet(uploaded,trial,409,"ANALYSIS_FAILED");
        expectGet("prd_missing",trial,404,"PRODUCT_NOT_FOUND");
    }

    @Test void patchRejectsEmptyUnknownPromptNullMalformedAndLimitsWithoutRevisionChange() throws Exception {
        String product=readyProduct(trial,null,"validation-analysis");
        invalidPatch(product,"{}",400);
        invalidPatch(product,"{\"generationPrompt\":\"client prompt\"}",422);
        invalidPatch(product,"{\"unknown\":\"value\"}",422);
        invalidPatch(product,"{\"idea\":null}",422);
        invalidPatch(product,"{\"description\":\"\"}",422);
        invalidPatch(product,"{\"title\":\""+"x".repeat(201)+"\"}",422);
        invalidPatch(product,"{broken",400);
        assertThat(jdbc.queryForObject("select revision from product_analyses where product_id=?",Integer.class,product)).isEqualTo(1);
    }

    @Test void bearerAndTrialOwnershipRemainSeparatedAndOriginIsRequiredOnlyForCookiePatch() throws Exception {
        String trialProduct=readyProduct(trial,null,"trial-owner-analysis");
        String token=bearer("analysis-owner@example.com");
        String userProduct=readyProduct(null,token,"user-owner-analysis");
        mvc.perform(get("/api/v1/products/"+trialProduct+"/analysis").cookie(trial)
                        .header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/"+userProduct+"/analysis").cookie(trial)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/"+userProduct+"/analysis").header("Authorization","Bearer "+token))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/products/"+trialProduct+"/analysis").cookie(trial)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"No origin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/products/"+userProduct+"/analysis").header("Authorization","Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Bearer update\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Bearer update"));
    }

    @Test void concurrentPartialPatchesEachIncrementRevisionOnceWithoutLostFields() throws Exception {
        String product=readyProduct(trial,null,"parallel-patch-analysis");
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var title=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return patchRequest(product,"{\"title\":\"Parallel title\"}");});
            var idea=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return patchRequest(product,"{\"idea\":\"Parallel idea\"}");});
            start.countDown();
            assertThat(title.get(15,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(idea.get(15,TimeUnit.SECONDS)).isEqualTo(200);
        }
        mvc.perform(get("/api/v1/products/"+product+"/analysis").cookie(trial))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.title").value("Parallel title"))
                .andExpect(jsonPath("$.idea").value("Parallel idea"));
    }

    private int patchRequest(String product,String body) throws Exception {
        return mvc.perform(patch("/api/v1/products/"+product+"/analysis").cookie(trial)
                .header("Origin","https://ui.test").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }
    private void invalidPatch(String product,String body,int status) throws Exception {
        mvc.perform(patch("/api/v1/products/"+product+"/analysis").cookie(trial)
                        .header("Origin","https://ui.test").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    private void expectGet(String product,Cookie cookie,int status,String code) throws Exception {
        mvc.perform(get("/api/v1/products/"+product+"/analysis").cookie(cookie))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private String readyProduct(Cookie cookie,String bearer,String key) throws Exception {
        String product=product(cookie,bearer);
        start(product,cookie,bearer,key);
        assertThat(worker.runOnce()).isTrue();
        return product;
    }
    private void start(String product,Cookie cookie,String bearer,String key) throws Exception {
        var request=post("/api/v1/products/"+product+"/analysis")
                .header("Idempotency-Key",key).header("Origin","https://ui.test");
        if(cookie!=null)request.cookie(cookie);
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        mvc.perform(request).andExpect(status().isAccepted());
    }
    private String product(Cookie cookie,String bearer) throws Exception {
        byte[] image;
        try(var input=getClass().getResourceAsStream("/images/product.png")){image=input.readAllBytes();}
        var request=multipart("/api/v1/products").file(new MockMultipartFile("image","source.png","image/png",image))
                .header("Origin","https://ui.test");
        if(cookie!=null)request.cookie(cookie);
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        var response=mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asString();
    }
    private String bearer(String email) {
        auth.register(email,"correct horse battery staple",null,null);
        return auth.login(email,"correct horse battery staple",new RequestMetadata("Test","127.0.0.xxx")).accessToken();
    }
}
