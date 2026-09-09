package com.tovarika.tech.project;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.docker.compose.enabled=false",
            "tovarika.storage.minio.initialize-bucket=false",
            "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "tovarika.security.password.breached-check-enabled=false"
        })
class ProjectsContractIntegrationTest {
    private static final String USER = "usr_owner";
    private static final String OTHER_USER = "usr_other";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired OpaqueTokenService tokens;
    @Autowired AuthenticationStore authenticationStore;
    @Autowired AuthenticationCookieService cookies;
    @Autowired WebApplicationContext applicationContext;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE projects, products, assets, trial_sessions, users CASCADE");
        insertUser(USER, "owner@example.test");
        insertUser(OTHER_USER, "other@example.test");
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
    }

    @Test
    void bearerCreatesProjectWithDefaultsAndReadsDto() throws Exception {
        insertUserProduct("prd_owned", "Кроссовки", USER);

        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_owned\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern("prj_[A-Za-z0-9]+")))
                .andExpect(jsonPath("$.name").value("Кроссовки"))
                .andExpect(jsonPath("$.productId").value("prd_owned"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("3:4"))
                .andExpect(jsonPath("$.cardCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.previewImage").doesNotExist())
                .andReturn();

        String projectId = JsonTestValue.string(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.name").value("Кроссовки"));
    }

    @Test
    void trialSessionCreatesAndReadsItsProject() throws Exception {
        String rawToken = "trial-secret";
        insertTrial("try_owner", rawToken);
        insertTrialProduct("prd_trial", "Trial product", "try_owner");
        Cookie cookie = new Cookie(cookies.trialCookieName(), rawToken);

        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_trial\",\"name\":\"My project\",\"defaultAspectRatio\":\"1:1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My project"))
                .andExpect(jsonPath("$.defaultAspectRatio").value("1:1"))
                .andReturn();

        String projectId = JsonTestValue.string(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("prd_trial"));
    }

    @Test
    void rejectsMissingAuthentication() throws Exception {
        insertUserProduct("prd_owned", "Product", USER);
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_owned\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsForeignProductWithForbidden() throws Exception {
        insertUserProduct("prd_foreign", "Foreign", OTHER_USER);
        mockMvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_foreign\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void returnsNotFoundForMissingProductAndForeignProject() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        insertUserProduct("prd_foreign", "Foreign", OTHER_USER);
        insertProject("prj_foreign", "prd_foreign");
        mockMvc.perform(get("/api/v1/projects/prj_foreign")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void duplicateProjectReturnsStableConflict() throws Exception {
        insertUserProduct("prd_owned", "Product", USER);
        insertProject("prj_existing", "prd_owned");
        mockMvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"prd_owned\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void listsEmptyPageForRegisteredUser() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .param("limit", "9")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.meta.limit").value(9))
                .andExpect(jsonPath("$.meta.nextCursor").doesNotExist());
    }

    @Test
    void paginatesByUpdatedAtAndIdAndIsolatesUsers() throws Exception {
        Instant base = Instant.parse("2026-09-09T10:00:00Z");
        insertUserProject("prd_old", "prj_old", base);
        insertUserProject("prd_tie_a", "prj_tiea", base.plusSeconds(10));
        insertUserProject("prd_tie_b", "prj_tieb", base.plusSeconds(10));
        insertUserProject("prd_new", "prj_new", base.plusSeconds(20));
        insertUserProduct("prd_foreign", "Foreign", OTHER_USER);
        insertProjectAt("prj_foreign", "prd_foreign", base.plusSeconds(30));

        MvcResult firstPage = mockMvc.perform(get("/api/v1/projects")
                        .param("limit", "2")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("prj_new"))
                .andExpect(jsonPath("$.items[1].id").value("prj_tieb"))
                .andExpect(jsonPath("$.meta.nextCursor").isNotEmpty())
                .andReturn();

        String cursor = JsonTestValue.string(
                firstPage.getResponse().getContentAsString(), "meta", "nextCursor");
        mockMvc.perform(get("/api/v1/projects")
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("prj_tiea"))
                .andExpect(jsonPath("$.items[1].id").value("prj_old"))
                .andExpect(jsonPath("$.meta.nextCursor").doesNotExist());
    }

    @Test
    void returnsPreviewInSameListResponse() throws Exception {
        insertAsset("asset_source", "source_image");
        insertProduct("prd_preview", "Preview product", USER, null, "asset_source");
        insertProject("prj_preview", "prd_preview");

        mockMvc.perform(get("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].previewImage.id").value("asset_source"))
                .andExpect(jsonPath("$.items[0].previewImage.purpose").value("source_image"))
                .andExpect(jsonPath("$.items[0].previewImage.url").value("https://cdn.test/asset_source"));
    }

    @Test
    void rejectsInvalidCursorLimitAndTrialHistory() throws Exception {
        var bearer = jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"));
        mockMvc.perform(get("/api/v1/projects").param("cursor", "not-a-cursor").with(bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/projects")
                        .param("limit", "101")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String rawToken = "trial-history-secret";
        insertTrial("try_history", rawToken);
        mockMvc.perform(get("/api/v1/projects")
                        .cookie(new Cookie(cookies.trialCookieName(), rawToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void convertedTrialProjectAppearsInUserHistory() throws Exception {
        String rawToken = "converted-trial-secret";
        insertTrial("try_converted", rawToken);
        insertTrialProduct("prd_converted", "Converted", "try_converted");
        insertProject("prj_converted", "prd_converted");

        org.assertj.core.api.Assertions.assertThat(
                        authenticationStore.convertTrial(tokens.hash(rawToken), USER, Instant.now()))
                .isTrue();

        mockMvc.perform(get("/api/v1/projects")
                        .with(jwt().jwt(jwt -> jwt.subject(USER).claim("sid", "ses_test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("prj_converted"));
    }

    private void insertUser(String id, String email) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into users (id, email, email_verified, status, created_at, updated_at) values (?, ?, true, 'ACTIVE', ?, ?)",
                id, email, Timestamp.from(now), Timestamp.from(now));
    }

    private void insertTrial(String id, String rawToken) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into trial_sessions (id, token_hash, created_at, expires_at) values (?, ?, ?, ?)",
                id, tokens.hash(rawToken), Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
    }

    private void insertUserProduct(String id, String name, String userId) {
        insertProduct(id, name, userId, null);
    }

    private void insertTrialProduct(String id, String name, String trialId) {
        insertProduct(id, name, null, trialId);
    }

    private void insertProduct(String id, String name, String userId, String trialId) {
        insertProduct(id, name, userId, trialId, null);
    }

    private void insertProduct(String id, String name, String userId, String trialId, String sourceAssetId) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into products (id, name, source_asset_id, owner_user_id, owner_trial_session_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                id, name, sourceAssetId, userId, trialId, Timestamp.from(now), Timestamp.from(now));
    }

    private void insertProject(String id, String productId) {
        insertProjectAt(id, productId, Instant.now());
    }

    private void insertProjectAt(String id, String productId, Instant updatedAt) {
        jdbc.update(
                "insert into projects (id, product_id, name, default_aspect_ratio, created_at, updated_at) values (?, ?, 'Existing', '3:4', ?, ?)",
                id, productId, Timestamp.from(updatedAt), Timestamp.from(updatedAt));
    }

    private void insertUserProject(String productId, String projectId, Instant updatedAt) {
        insertUserProduct(productId, projectId, USER);
        insertProjectAt(projectId, productId, updatedAt);
    }

    private void insertAsset(String id, String purpose) {
        jdbc.update(
                """
                insert into assets
                    (id, purpose, media_type, size_bytes, width, height, url, created_at)
                values (?, ?, 'image/webp', 1024, 1200, 1600, ?, ?)
                """,
                id,
                purpose,
                "https://cdn.test/" + id,
                Timestamp.from(Instant.now()));
    }

    private static final class JsonTestValue {
        static String string(String json, String field) throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asText();
        }

        static String string(String json, String object, String field) throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(json)
                    .get(object)
                    .get(field)
                    .asText();
        }
    }
}
