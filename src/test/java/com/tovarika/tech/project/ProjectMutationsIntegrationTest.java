package com.tovarika.tech.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.docker.compose.enabled=false",
    "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false",
    "tovarika.security.cors.allowed-origins=https://ui.test"
})
class ProjectMutationsIntegrationTest {
    private static final String PATH = "/api/v1/projects/prj_owned";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationStore store;
    @Autowired SessionService sessions;
    @Autowired OpaqueTokenService tokens;
    @Autowired AuthenticationCookieService cookies;
    @Autowired WebApplicationContext context;
    @Autowired TransactionTemplate transactions;
    @Autowired ProjectService projects;

    MockMvc mvc;
    String accessToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, trial_sessions, products, projects, templates, cards, project_jobs, assets CASCADE");
        jdbc.update("""
                insert into users (id, email, email_verified, status, created_at, updated_at)
                values ('usr_owner', 'owner@example.test', true, 'ACTIVE', now(), now()),
                       ('usr_other', 'other@example.test', true, 'ACTIVE', now(), now())
                """);
        accessToken = sessions.create(store.findUserById("usr_owner").orElseThrow(),
                new RequestMetadata("Test Browser", "192.0.2.xxx")).accessToken();
        jdbc.update("""
                insert into products (id, name, owner_user_id, created_at, updated_at)
                values ('prd_owned', 'Product', 'usr_owner', now(), now())
                """);
        jdbc.update("insert into templates (id, available) values ('tpl_active', true), ('tpl_hidden', false)");
        jdbc.update("""
                insert into projects (id, product_id, name, selected_template_id, default_aspect_ratio, created_at, updated_at)
                values ('prj_owned', 'prd_owned', 'Original', 'tpl_active', '3:4', ?, ?)
                """, Timestamp.from(CREATED), Timestamp.from(CREATED));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bearerPatchesFieldsIndependentlyAndPreservesProjectData() throws Exception {
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.selectedTemplateId").value("tpl_active"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("3:4"))
                .andExpect(jsonPath("$.productId").value("prd_owned"))
                .andExpect(jsonPath("$.cardCount").value(0));
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultAspectRatio\":\"1:1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("1:1"));
        jdbc.update("insert into templates (id) values ('tpl_second')");
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedTemplateId\":\"tpl_second\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("1:1"))
                .andExpect(jsonPath("$.selectedTemplateId").value("tpl_second"));
        assertThat(jdbc.queryForObject("select created_at from projects", Timestamp.class).toInstant()).isEqualTo(CREATED);
        assertThat(jdbc.queryForObject("select updated_at from projects", Timestamp.class).toInstant()).isAfter(CREATED);
    }

    @Test
    void bearerUpdatesAllSettingsTogether() throws Exception {
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"All\",\"selectedTemplateId\":\"tpl_active\",\"defaultAspectRatio\":\"16:9\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("All"))
                .andExpect(jsonPath("$.selectedTemplateId").value("tpl_active"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("16:9"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "", "{\"name\":\"\"}",
        "{\"selectedTemplateId\":\"invalid\"}", "{\"defaultAspectRatio\":\"2:3\"}",
        "{\"name\":null}", "{\"name\":\"Valid\",\"selectedTemplateId\":null}",
        "{\"defaultAspectRatio\":null}", "{\"unknown\":true}",
        "{\"name\":\"Valid\",\"productId\":\"prd_other\"}", "{\"name\":123}"})
    void rejectsInvalidPatchWithoutChangingState(String payload) throws Exception {
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertUnchanged();
    }

    @Test
    void rejectsOversizedName() throws Exception {
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tpl_missing", "tpl_hidden"})
    void unavailableTemplateRollsBackAllFields(String templateId) throws Exception {
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"selectedTemplateId\":\"" + templateId + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
        assertUnchanged();
    }

    @Test
    void rejectsMissingInvalidAndRevokedBearerCredentials() throws Exception {
        for (var request : mutations(PATH)) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (var request : mutations(PATH)) {
            mvc.perform(request.header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
        }
        sessions.logoutAll("usr_owner");
        for (var request : mutations(PATH)) {
            mvc.perform(bearer(request)).andExpect(status().isUnauthorized());
        }
        assertUnchanged();
    }

    @Test
    void hidesForeignAndMissingProjectsForBothMutations() throws Exception {
        jdbc.update("update products set owner_user_id = 'usr_other'");
        for (String path : List.of(PATH, "/api/v1/projects/prj_missing")) {
            for (var request : mutations(path)) {
                mvc.perform(bearer(request)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
            }
        }
        assertUnchanged();
    }

    @Test
    void trialCanPatchAndDeleteWithAllowedOrigin() throws Exception {
        Cookie trial = trial(true);
        mvc.perform(patch(PATH).cookie(trial).header("Origin", "https://ui.test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Trial\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Trial"));
        insertCard();
        mvc.perform(delete(PATH).cookie(trial).header("Origin", "https://ui.test"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(count("projects")).isZero();
        assertThat(count("cards")).isZero();
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    void trialRequiresAllowedOriginAndCannotMutateForeignProject() throws Exception {
        Cookie trial = trial(false);
        for (var request : mutations(PATH)) {
            mvc.perform(request.cookie(trial)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        for (var request : mutations(PATH)) {
            mvc.perform(request.cookie(trial).header("Origin", "https://evil.test"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        for (var request : mutations(PATH)) {
            mvc.perform(request.cookie(trial).header("Origin", "https://ui.test"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        }
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "converted", "unknown"})
    void rejectsUnusableTrialSession(String scenario) throws Exception {
        Cookie trial = trial(true);
        switch (scenario) {
            case "expired" -> jdbc.update("update trial_sessions set expires_at = now() - interval '1 minute'");
            case "converted" -> store.convertTrial(tokens.hash("trial-secret"), "usr_owner", Instant.now());
            case "unknown" -> trial.setValue("unknown");
        }
        for (var request : mutations(PATH)) {
            mvc.perform(request.cookie(trial).header("Origin", "https://ui.test"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TRIAL_SESSION_NOT_FOUND"));
        }
        assertUnchanged();
    }

    @Test
    void bearerTakesPrecedenceOverTrialCookie() throws Exception {
        Cookie trial = trial(false);
        mvc.perform(bearer(patch(PATH)).cookie(trial).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bearer\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(delete(PATH)).cookie(trial)).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "processing"})
    void activeJobsPreventBothMutations(String jobStatus) throws Exception {
        insertCard();
        insertJob(jobStatus);
        for (var request : mutations(PATH)) {
            mvc.perform(bearer(request)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CARD_BUSY"));
        }
        assertUnchanged();
        assertThat(count("cards")).isEqualTo(1);
        assertThat(count("project_jobs")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed"})
    void deletesCardsAndTerminalJobsButRetainsProductAndAssets(String jobStatus) throws Exception {
        insertCard();
        insertJob(jobStatus);
        jdbc.update("""
                insert into assets (id, purpose, media_type, size_bytes, url, created_at)
                values ('asset_saved', 'card_image', 'image/webp', 1, 'https://cdn.test/saved', now())
                """);
        jdbc.update("update cards set image_asset_id = 'asset_saved'");
        jdbc.update("update projects set preview_asset_id = 'asset_saved', card_count = 1");
        jdbc.update("update products set source_asset_id = 'asset_saved'");
        mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Done\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previewImage.id").value("asset_saved"))
                .andExpect(jsonPath("$.cardCount").value(1));
        mvc.perform(bearer(delete(PATH))).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(count("projects")).isZero();
        assertThat(count("cards")).isZero();
        assertThat(count("project_jobs")).isZero();
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("assets")).isEqualTo(1);
        mvc.perform(bearer(delete(PATH))).andExpect(status().isNotFound());
        assertThat(projects.create(ProjectOwner.user("usr_owner"), "prd_owned", null, null).productId()).isEqualTo("prd_owned");
    }

    @Test
    void failedDeleteRollsBackProjectAndCardDeletionTogether() {
        insertCard();
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(transaction -> {
            projects.delete("prj_owned", ProjectOwner.user("usr_owner"));
            assertThat(count("projects")).isZero();
            assertThat(count("cards")).isZero();
            throw new IllegalStateException("Simulated failure before commit");
        }));
        assertUnchanged();
        assertThat(count("cards")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"patch", "delete"})
    void concurrentJobStartIsObservedBeforeMutation(String method) throws Exception {
        CountDownLatch jobInserted = new CountDownLatch(1);
        CountDownLatch releaseJob = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var job = executor.submit(() -> transactions.executeWithoutResult(transaction -> {
                insertJob("queued");
                jobInserted.countDown();
                await(releaseJob);
            }));
            try {
                assertThat(jobInserted.await(10, TimeUnit.SECONDS)).isTrue();
                var mutation = executor.submit(() -> {
                    mutationStarted.countDown();
                    var request = "patch".equals(method)
                            ? patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Concurrent\"}")
                            : delete(PATH);
                    return mvc.perform(bearer(request)).andReturn();
                });
                assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                releaseJob.countDown();
                job.get(10, TimeUnit.SECONDS);
                var result = mutation.get(10, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(result.getResponse().getContentAsString()).contains("CARD_BUSY");
                assertUnchanged();
            } finally {
                releaseJob.countDown();
            }
        }
    }

    @Test
    void terminalJobCannotReactivateOrMoveToAnotherProject() {
        insertJob("completed");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update project_jobs set status = 'queued'"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update project_jobs set project_id = 'prj_missing'"));
        assertThat(jdbc.queryForObject("select status from project_jobs", String.class)).isEqualTo("completed");
    }

    @Test
    void unrelatedProjectCardsAndActiveJobsAreUnaffectedByDeletion() throws Exception {
        jdbc.update("""
                insert into products (id, name, owner_user_id, created_at, updated_at)
                values ('prd_other', 'Other', 'usr_other', now(), now())
                """);
        jdbc.update("""
                insert into projects (id, product_id, name, default_aspect_ratio, created_at, updated_at)
                values ('prj_other', 'prd_other', 'Other', '3:4', now(), now())
                """);
        insertCard();
        jdbc.update("update cards set project_id = 'prj_other'");
        jdbc.update("""
                insert into project_jobs (id, project_id, type, status, created_at, updated_at)
                values ('job_other', 'prj_other', 'export', 'processing', now(), now())
                """);
        mvc.perform(bearer(delete(PATH))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select id from projects", String.class)).isEqualTo("prj_other");
        assertThat(jdbc.queryForObject("select project_id from cards", String.class)).isEqualTo("prj_other");
        assertThat(jdbc.queryForObject("select status from project_jobs", String.class)).isEqualTo("processing");
        assertThat(count("products")).isEqualTo(2);
    }

    @Test
    void concurrentPartialUpdatesPreserveBothChanges() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rename = executor.submit(() -> {
                await(start);
                return mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Concurrent\"}")).andReturn().getResponse().getStatus();
            });
            var ratio = executor.submit(() -> {
                await(start);
                return mvc.perform(bearer(patch(PATH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultAspectRatio\":\"4:5\"}")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(rename.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(ratio.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select name from projects", String.class)).isEqualTo("Concurrent");
        assertThat(jdbc.queryForObject("select default_aspect_ratio from projects", String.class)).isEqualTo("4:5");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + accessToken);
    }

    private List<MockHttpServletRequestBuilder> mutations(String path) {
        return List.of(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}"), delete(path));
    }

    private Cookie trial(boolean ownsProject) {
        jdbc.update("""
                insert into trial_sessions (id, token_hash, created_at, expires_at)
                values ('try_owner', ?, now(), now() + interval '1 hour')
                """, tokens.hash("trial-secret"));
        if (ownsProject) jdbc.update("update products set owner_user_id = null, owner_trial_session_id = 'try_owner'");
        return new Cookie(cookies.trialCookieName(), "trial-secret");
    }

    private void insertCard() {
        jdbc.update("""
                insert into cards (id, project_id, position, status, aspect_ratio, template_id, prompt, idea, created_at, updated_at)
                values ('card_owned', 'prj_owned', 1, 'ready', '3:4', 'tpl_active', 'Prompt', 'Idea', now(), now())
                """);
    }

    private void insertJob(String jobStatus) {
        jdbc.update("""
                insert into project_jobs (id, project_id, type, status, created_at, updated_at)
                values ('job_owned', 'prj_owned', 'card_generation', ?, now(), now())
                """, jobStatus);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("select name from projects where id = 'prj_owned'", String.class)).isEqualTo("Original");
        assertThat(jdbc.queryForObject("select updated_at from projects where id = 'prj_owned'", Timestamp.class).toInstant()).isEqualTo(CREATED);
    }
}
