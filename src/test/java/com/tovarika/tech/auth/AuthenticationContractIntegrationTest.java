package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.assertThat;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.tech.billing.application.BillingService;
import com.tovarika.tech.billing.application.BillingFailure;
import com.tovarika.tech.trial.application.TrialSessionService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.EmailAuthenticationService;
import com.tovarika.tech.auth.application.OAuthCompletion;
import com.tovarika.tech.auth.application.OAuthStart;
import com.tovarika.tech.auth.application.RefreshReuseDetectedException;
import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.auth.application.SessionGrant;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.YandexOAuthService;
import com.tovarika.tech.auth.application.port.AuthenticationMessageSender;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.application.port.BreachedPasswordChecker;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.auth.application.port.YandexOAuthClient;
import com.tovarika.tech.auth.domain.AuthenticationSession;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
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
            "tovarika.security.password.breached-check-enabled=false",
            "tovarika.security.cookie.secure=true",
            "tovarika.security.cors.allowed-origins=https://ui.test",
            "tovarika.security.oauth.yandex.client-id=test-client",
            "tovarika.security.oauth.yandex.client-secret=test-secret",
            "tovarika.security.oauth.yandex.redirect-uri=https://api.test/api/v1/auth/oauth/yandex/callback"
        })
@Import(AuthenticationContractIntegrationTest.TestDoubles.class)
class AuthenticationContractIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "another long secure passphrase";
    private static final RequestMetadata METADATA = new RequestMetadata("Test Browser", "192.0.2.xxx");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired EmailAuthenticationService emailAuthentication;
    @Autowired SessionService sessions;
    @Autowired YandexOAuthService yandexOAuth;
    @Autowired AuthenticationStore store;
    @Autowired OpaqueTokenService opaqueTokens;
    @Autowired AuthenticationProperties properties;
    @Autowired AuthenticationCookieService cookies;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturingMessageSender messages;
    @Autowired FakeYandexOAuthClient yandexClient;
    @Autowired WebApplicationContext applicationContext;
    @Autowired TransactionTemplate transactions;
    @Autowired BillingService billing;
    @Autowired TrialSessionService trialService;
    @Autowired com.tovarika.tech.cards.CardGenerationStore generationStore;
    @Autowired com.tovarika.tech.cards.CardGenerationService generationService;
    @Autowired com.tovarika.tech.cards.editing.application.CardImageEditingService editingService;
    @Autowired com.tovarika.tech.cards.editing.application.ImageEditingStore editingStore;
    @Autowired com.tovarika.tech.analyses.application.AnalysisService analysisService;
    @Autowired com.tovarika.tech.analyses.application.AnalysisStore analysisStore;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE TABLE billing_accounts, authentication_rate_limits, oauth_attempts, password_reset_tokens,
                    refresh_tokens, auth_sessions, auth_identities, trial_sessions, users CASCADE
                """);
        // Exercise historical grants/conversion with the original published price fixture.
        jdbc.update("update billing_configuration set active_version='billing-2026-10-14-v1'");
        messages.clear();
        yandexClient.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
    }

    @AfterEach
    void resetProvider() {
        yandexClient.reset();
    }

    @Test
    void register_returns_active_user() throws Exception {
        String body = """
                {"email":"register-active@example.com","password":"%s","displayName":"Admin"}
                """.formatted(PASSWORD);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.status").value("active"));
    }

    @Test
    void refresh_rotation_single_use() {
        SessionGrant first = activeAccount("rotation@example.com");
        SessionGrant second = sessions.rotate(first.rawRefreshToken(), METADATA);

        assertThat(second.rawRefreshToken()).isNotEqualTo(first.rawRefreshToken());
        assertThatThrownBy(() -> sessions.rotate(first.rawRefreshToken(), METADATA))
                .isInstanceOf(RefreshReuseDetectedException.class);
    }

    @Test
    void refresh_reuse_revokes_family() {
        SessionGrant first = activeAccount("reuse@example.com");
        SessionGrant second = sessions.rotate(first.rawRefreshToken(), METADATA);

        assertThrows(RefreshReuseDetectedException.class, () -> sessions.rotate(first.rawRefreshToken(), METADATA));

        assertThat(session(first.sessionId()).isRevoked()).isTrue();
        assertThatThrownBy(() -> sessions.rotate(second.rawRefreshToken(), METADATA))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.SESSION_REVOKED));
    }

    @Test
    void parallel_refresh_same_token_only_one_success() throws Exception {
        SessionGrant first = activeAccount("parallel@example.com");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Object> request = () -> {
                ready.countDown();
                start.await();
                try {
                    return sessions.rotate(first.rawRefreshToken(), METADATA);
                } catch (AuthException failure) {
                    return failure;
                }
            };
            Future<Object> a = executor.submit(request);
            Future<Object> b = executor.submit(request);
            ready.await();
            start.countDown();
            List<Object> results = List.of(a.get(), b.get());

            assertThat(results).filteredOn(SessionGrant.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(RefreshReuseDetectedException.class::isInstance).hasSize(1);
        }
        assertThat(session(first.sessionId()).isRevoked()).isTrue();
    }

    @Test
    void reset_token_single_use() {
        activeAccount("reset-once@example.com");
        emailAuthentication.requestPasswordReset("reset-once@example.com");
        String token = messages.resetToken("reset-once@example.com");

        emailAuthentication.confirmPasswordReset(token, NEW_PASSWORD);

        assertThatThrownBy(() -> emailAuthentication.confirmPasswordReset(token, NEW_PASSWORD))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID));
    }

    @Test
    void reset_revokes_sessions() {
        SessionGrant first = activeAccount("reset-sessions@example.com");
        SessionGrant second = emailAuthentication.login("reset-sessions@example.com", PASSWORD, METADATA);
        emailAuthentication.requestPasswordReset("reset-sessions@example.com");

        emailAuthentication.confirmPasswordReset(messages.resetToken("reset-sessions@example.com"), NEW_PASSWORD);

        assertThat(session(first.sessionId()).isRevoked()).isTrue();
        assertThat(session(second.sessionId()).isRevoked()).isTrue();
    }

    @Test
    void password_change_rotates_current_and_revokes_others() {
        SessionGrant current = activeAccount("change@example.com");
        SessionGrant other = emailAuthentication.login("change@example.com", PASSWORD, METADATA);

        SessionGrant rotated = emailAuthentication.changePassword(
                current.user().user().id(),
                current.sessionId(),
                current.rawRefreshToken(),
                PASSWORD,
                NEW_PASSWORD,
                METADATA);

        assertThat(rotated.rawRefreshToken()).isNotEqualTo(current.rawRefreshToken());
        assertThat(session(current.sessionId()).isRevoked()).isFalse();
        assertThat(session(other.sessionId()).isRevoked()).isTrue();
        assertThat(emailAuthentication.login("change@example.com", NEW_PASSWORD, METADATA)).isNotNull();
    }

    @Test
    void enumeration_safe_password_reset_requests() throws Exception {
        emailAuthentication.register("known@example.com", PASSWORD, null, null);
        String knownEmail = "{\"email\":\"known@example.com\"}";
        String unknownEmail = "{\"email\":\"unknown@example.com\"}";

        mockMvc.perform(post("/api/v1/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knownEmail))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownEmail))
                .andExpect(status().isAccepted());
    }

    @Test
    void unknown_user_and_wrong_password_return_same_error() throws Exception {
        activeAccount("known-login@example.com");

        AuthException unknown = assertThrows(AuthException.class, () ->
                emailAuthentication.login("unknown-login@example.com", "wrong-password", METADATA));
        AuthException wrong = assertThrows(AuthException.class, () ->
                emailAuthentication.login("known-login@example.com", "wrong-password", METADATA));

        assertThat(unknown.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(wrong.code()).isEqualTo(unknown.code());
        assertThat(wrong.status()).isEqualTo(unknown.status());
        assertThat(wrong.getMessage()).isEqualTo(unknown.getMessage());

        String unknownBody = "{\"email\":\"missing@example.com\",\"password\":\"wrong-password\"}";
        String wrongBody = "{\"email\":\"known-login@example.com\",\"password\":\"wrong-password\"}";
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(unknownBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void blocked_user_cannot_authenticate() {
        SessionGrant account = activeAccount("blocked@example.com");
        jdbc.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", account.user().user().id());

        assertThatThrownBy(() -> emailAuthentication.login("blocked@example.com", PASSWORD, METADATA))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void blocked_user_access_jwt_rejected_immediately() throws Exception {
        SessionGrant grant = activeAccount("blocked-access@example.com");
        jdbc.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", grant.user().user().id());

        assertThrows(JwtException.class, () -> jwtDecoder.decode(grant.accessToken()));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    void refresh_cookie_is_secure_httponly_and_absent_from_json() throws Exception {
        activeAccount("cookie-flags@example.com");
        String request = "{\"email\":\"cookie-flags@example.com\",\"password\":\"" + PASSWORD + "\"}";

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"),
                        org.hamcrest.Matchers.containsString("Path=/"))))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void refresh_absolute_expiration() {
        SessionGrant grant = activeAccount("absolute@example.com");
        Instant now = Instant.now();
        jdbc.update(
                "UPDATE auth_sessions SET created_at = ?, last_activity_at = ?, absolute_expires_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(31L * 86400)),
                Timestamp.from(now.minusSeconds(2L * 86400)),
                Timestamp.from(now.minusSeconds(86400)),
                grant.sessionId());

        assertSessionRevoked(() -> sessions.rotate(grant.rawRefreshToken(), METADATA));
    }

    @Test
    void refresh_inactivity_expiration() {
        SessionGrant grant = activeAccount("inactive@example.com");
        Instant now = Instant.now();
        jdbc.update(
                "UPDATE auth_sessions SET created_at = ?, last_activity_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(20L * 86400)),
                Timestamp.from(now.minusSeconds(15L * 86400)),
                grant.sessionId());

        assertSessionRevoked(() -> sessions.rotate(grant.rawRefreshToken(), METADATA));
    }

    @Test
    void logout_revokes_current_session() {
        SessionGrant grant = activeAccount("logout@example.com");

        sessions.logout(grant.rawRefreshToken());
        sessions.logout(grant.rawRefreshToken());

        assertThat(session(grant.sessionId()).isRevoked()).isTrue();
    }

    @Test
    void logout_all_revokes_all_sessions() {
        SessionGrant first = activeAccount("logout-all@example.com");
        SessionGrant second = emailAuthentication.login("logout-all@example.com", PASSWORD, METADATA);

        sessions.logoutAll(first.user().user().id());

        assertThat(session(first.sessionId()).isRevoked()).isTrue();
        assertThat(session(second.sessionId()).isRevoked()).isTrue();
    }

    @Test
    void revoke_single_auth_session() {
        SessionGrant first = activeAccount("single-session@example.com");
        SessionGrant second = emailAuthentication.login("single-session@example.com", PASSWORD, METADATA);

        sessions.revoke(first.user().user().id(), second.sessionId());

        assertThat(session(first.sessionId()).isRevoked()).isFalse();
        assertThat(session(second.sessionId()).isRevoked()).isTrue();
    }

    @Test
    void session_list_only_returns_active_owned_sessions_and_retains_history() throws Exception {
        SessionGrant current = activeAccount("session-list@example.com");
        var user = current.user().user();
        SessionGrant otherActive = sessions.create(user, METADATA);
        SessionGrant revoked = sessions.create(user, METADATA);
        SessionGrant absoluteExpired = sessions.create(user, METADATA);
        SessionGrant inactivityExpired = sessions.create(user, METADATA);
        activeAccount("foreign-session-list@example.com");
        sessions.revoke(user.id(), revoked.sessionId());

        Instant now = Instant.now();
        jdbc.update(
                "UPDATE auth_sessions SET created_at = ?, last_activity_at = ?, absolute_expires_at = ? WHERE id = ?",
                Timestamp.from(now.minus(properties.refresh().absoluteTtl()).minusSeconds(60)),
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.minusSeconds(60)),
                absoluteExpired.sessionId());
        jdbc.update(
                "UPDATE auth_sessions SET created_at = ?, last_activity_at = ? WHERE id = ?",
                Timestamp.from(now.minus(properties.refresh().inactivityTtl()).minusSeconds(120)),
                Timestamp.from(now.minus(properties.refresh().inactivityTtl()).minusSeconds(60)),
                inactivityExpired.sessionId());

        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(
                        org.hamcrest.Matchers.contains(otherActive.sessionId(), current.sessionId())))
                .andExpect(jsonPath("$.items[0].current").value(false))
                .andExpect(jsonPath("$.items[1].current").value(true));

        mockMvc.perform(delete("/api/v1/auth/sessions/{sessionId}", otherActive.sessionId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(current.sessionId()))
                .andExpect(jsonPath("$.items[0].current").value(true));

        assertThat(session(otherActive.sessionId()).isRevoked()).isTrue();
        assertThat(session(revoked.sessionId()).isRevoked()).isTrue();
        assertThat(session(absoluteExpired.sessionId()).isRevoked()).isFalse();
        assertThat(session(inactivityExpired.sessionId()).isRevoked()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, user.id()))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id WHERE s.user_id = ?",
                Integer.class, user.id()))
                .isEqualTo(5);
    }

    @Test
    void valid_access_jwt_accepted() throws Exception {
        SessionGrant grant = activeAccount("jwt-valid@example.com");

        assertThat(jwtDecoder.decode(grant.accessToken()).getSubject()).isEqualTo(grant.user().user().id());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(grant.user().user().id()));
    }

    @Test
    void expired_access_jwt_rejected() {
        SessionGrant grant = activeAccount("jwt-expired@example.com");
        Instant now = Instant.now();
        String token = customJwt(
                grant, properties.jwt().issuer().toString(), properties.jwt().audience(), now.minusSeconds(120), now.minusSeconds(60), signingKey());

        assertThrows(JwtException.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void invalid_signature_rejected() {
        SessionGrant grant = activeAccount("jwt-signature@example.com");
        byte[] wrongKeyBytes = new byte[32];
        java.util.Arrays.fill(wrongKeyBytes, (byte) 1);
        SecretKey wrongKey = new SecretKeySpec(wrongKeyBytes, "HmacSHA256");
        String token = customJwt(
                grant, properties.jwt().issuer().toString(), properties.jwt().audience(), Instant.now(), Instant.now().plusSeconds(600), wrongKey);

        assertThrows(JwtException.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void wrong_issuer_rejected() {
        SessionGrant grant = activeAccount("jwt-issuer@example.com");
        String token = customJwt(
                grant, "https://wrong-issuer.test", properties.jwt().audience(), Instant.now(), Instant.now().plusSeconds(600), signingKey());

        assertThrows(JwtException.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void wrong_audience_rejected() {
        SessionGrant grant = activeAccount("jwt-audience@example.com");
        String token = customJwt(
                grant, properties.jwt().issuer().toString(), "wrong-audience", Instant.now(), Instant.now().plusSeconds(600), signingKey());

        assertThrows(JwtException.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void cookie_mutation_invalid_origin_rejected() throws Exception {
        SessionGrant grant = activeAccount("origin@example.com");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "https://evil.test")
                        .cookie(new Cookie(cookies.refreshCookieName(), grant.rawRefreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void cors_unknown_origin_rejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header("Origin", "https://evil.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void oauth_browser_callback_creates_account_and_refresh_session_then_reuses_identity() throws Exception {
        String returnPath = "/projects/prj_oauth/editor?step=generate&mode=review";
        OAuthStart start = yandexOAuth.start(returnPath);
        assertThat(queryParameter(start.authorizationUrl(), "scope")).isEqualTo("login:email login:info");
        assertThat(queryParameter(start.authorizationUrl(), "code_challenge_method")).isEqualTo("S256");
        var response = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()))
                        .param("state", queryParameter(start.authorizationUrl(), "state"))
                        .param("code", "synthetic-provider-code"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse();
        var location = UriComponentsBuilder.fromUriString(response.getHeader("Location")).build();
        assertThat(location.getPath()).isEqualTo("/auth/yandex/callback");
        assertThat(queryParameter(java.net.URI.create(response.getHeader("Location")), "return")).isEqualTo(returnPath);
        assertThat(location.getQueryParams()).doesNotContainKeys("code", "state", "error", "error_description");
        Cookie refresh = response.getCookie(cookies.refreshCookieName());
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getSecure()).isTrue();
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh).header("Origin", "https://ui.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        assertThat(jdbc.queryForObject("select count(*) from registration_onboarding where completed_at is null", Integer.class)).isEqualTo(1);
        var again = completeOAuth(null);
        assertThat(again.grant().user().user().email()).isEqualTo("yandex@example.com");
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_identities", Integer.class)).isEqualTo(1);
    }

    @Test
    void oauth_browser_cancel_preserves_trial_and_project_without_forwarding_provider_details() throws Exception {
        Cookie trial = newTrialCookie();
        OAuthStart start = yandexOAuth.start("/projects/prj_oauth/editor?step=generate");
        var response = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(trial, new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()))
                        .param("state", queryParameter(start.authorizationUrl(), "state"))
                        .param("error", "access_denied")
                        .param("error_description", "untrusted-provider-description"))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        java.net.URI location = java.net.URI.create(response.getHeader("Location"));
        assertThat(queryParameter(location, "error")).isEqualTo("access_denied");
        assertThat(queryParameter(location, "return")).isEqualTo("/projects/prj_oauth/editor?step=generate");
        assertThat(location.toString()).doesNotContain("untrusted-provider-description");
        assertThat(response.getCookie(cookies.refreshCookieName())).isNull();
        assertThat(response.getCookie(cookies.trialCookieName())).isNull();
        assertThat(response.getCookie(cookies.oauthCookieName()).getMaxAge()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions", Integer.class)).isZero();
    }

    @Test
    void oauth_browser_invalid_state_falls_back_and_cannot_masquerade_as_cancellation() throws Exception {
        OAuthStart start = yandexOAuth.start("/projects/prj_oauth/editor");
        var response = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()))
                        .param("state", "wrong-state").param("error", "access_denied"))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        java.net.URI location = java.net.URI.create(response.getHeader("Location"));
        assertThat(queryParameter(location, "error")).isEqualTo("OAUTH_STATE_INVALID");
        assertThat(queryParameter(location, "return")).isEqualTo("/editor");
        assertThat(response.getCookie(cookies.refreshCookieName())).isNull();
    }

    @Test
    void oauth_browser_provider_failure_returns_to_project_and_replayed_callback_is_rejected() throws Exception {
        OAuthStart start = yandexOAuth.start("/projects/prj_oauth/editor");
        String state = queryParameter(start.authorizationUrl(), "state");
        yandexClient.rejectPkce = true;
        var failure = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()))
                        .param("state", state).param("code", "synthetic-provider-code"))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        assertThat(queryParameter(java.net.URI.create(failure.getHeader("Location")), "error")).isEqualTo("OAUTH_PROVIDER_ERROR");
        assertThat(queryParameter(java.net.URI.create(failure.getHeader("Location")), "return")).isEqualTo("/projects/prj_oauth/editor");
        yandexClient.rejectPkce = false;
        yandexOAuth.complete(start.rawCorrelationToken(), state, "synthetic-provider-code", null, null, METADATA);
        var replay = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()))
                        .param("state", state).param("code", "synthetic-provider-code"))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        assertThat(queryParameter(java.net.URI.create(replay.getHeader("Location")), "error")).isEqualTo("OAUTH_STATE_INVALID");
        assertThat(replay.getCookie(cookies.refreshCookieName())).isNull();
    }

    @Test
    void oauth_browser_expired_attempt_and_missing_cookie_require_a_fresh_start() throws Exception {
        OAuthStart start = yandexOAuth.start("/projects/prj_oauth/editor");
        jdbc.update("update oauth_attempts set created_at=?, expires_at=?",
                Timestamp.from(Instant.now().minusSeconds(600)), Timestamp.from(Instant.now().minusSeconds(60)));
        for (boolean withCookie : List.of(true, false)) {
            var request = get("/api/v1/auth/oauth/yandex/callback")
                    .param("state", queryParameter(start.authorizationUrl(), "state"))
                    .param("code", "synthetic-provider-code");
            if (withCookie) request.cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()));
            var response = mockMvc.perform(request).andExpect(status().isSeeOther()).andReturn().getResponse();
            java.net.URI location = java.net.URI.create(response.getHeader("Location"));
            assertThat(queryParameter(location, "error")).isEqualTo("OAUTH_STATE_INVALID");
            assertThat(queryParameter(location, "return")).isEqualTo("/editor");
            assertThat(response.getCookie(cookies.refreshCookieName())).isNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions", Integer.class)).isZero();
    }

    @Test
    void oauth_browser_invalid_trial_is_cleared_to_allow_a_fresh_login() throws Exception {
        OAuthStart start = yandexOAuth.start("/editor");
        var response = mockMvc.perform(get("/api/v1/auth/oauth/yandex/callback")
                        .cookie(new Cookie(cookies.oauthCookieName(), start.rawCorrelationToken()),
                                new Cookie(cookies.trialCookieName(), "synthetic-expired-trial"))
                        .param("state", queryParameter(start.authorizationUrl(), "state"))
                        .param("code", "synthetic-provider-code"))
                .andExpect(status().isSeeOther()).andReturn().getResponse();
        assertThat(queryParameter(java.net.URI.create(response.getHeader("Location")), "error")).isEqualTo("TRIAL_SESSION_NOT_FOUND");
        assertThat(response.getCookie(cookies.trialCookieName()).getMaxAge()).isZero();
        assertThat(response.getCookie(cookies.refreshCookieName())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        assertThat(completeOAuth(null).grant().user().user().email()).isEqualTo("yandex@example.com");
    }

    @Test
    void oauth_email_match_does_not_take_over_an_email_account() {
        var existing = emailAuthentication.register("yandex@example.com", PASSWORD, null, null);
        var completion = completeOAuth(null);
        assertThat(completion.grant().user().user().id()).isNotEqualTo(existing.user().id());
        assertThat(store.findProviders(existing.user().id())).containsExactly(com.tovarika.tech.auth.domain.AuthProvider.EMAIL);
        assertThat(completion.grant().user().providers()).containsExactly(com.tovarika.tech.auth.domain.AuthProvider.YANDEX);
    }

    @Test
    void oauth_state_mismatch_rejected() {
        OAuthStart start = yandexOAuth.start("/editor");

        assertThatThrownBy(() -> yandexOAuth.complete(
                        start.rawCorrelationToken(), "wrong-state", "provider-code", null, null, METADATA))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isZero();
    }

    @Test
    void oauth_pkce_mismatch_rejected() {
        OAuthStart start = yandexOAuth.start("/editor");
        String state = queryParameter(start.authorizationUrl(), "state");
        yandexClient.rejectPkce = true;

        assertThatThrownBy(() -> yandexOAuth.complete(
                        start.rawCorrelationToken(), state, "provider-code", null, null, METADATA))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.OAUTH_PROVIDER_ERROR));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities WHERE provider = 'YANDEX'", Integer.class))
                .isZero();
    }

    @Test
    void external_return_url_rejected() {
        assertThatThrownBy(() -> yandexOAuth.start("https://evil.test/callback"))
                .isInstanceOfSatisfying(AuthException.class, error ->
                        assertThat(error.code()).isEqualTo(AuthErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> yandexOAuth.start("//evil.test"))
                .isInstanceOf(AuthException.class);
        for (String path : List.of("/editor/../auth", "/editor/%2e%2e/auth", "/editor/%252e%252e/auth", "/editor/%5coutside", "/editor/%00", "/editor/%")) {
            assertThatThrownBy(() -> yandexOAuth.start(path)).isInstanceOf(AuthException.class);
        }
    }

    @Test
    void secrets_absent_from_logs_and_urls() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            OAuthStart start = yandexOAuth.start("/editor");
            String state = queryParameter(start.authorizationUrl(), "state");
            String providerCode = "provider-code-must-not-be-logged";

            OAuthCompletion completion = yandexOAuth.complete(
                    start.rawCorrelationToken(), state, providerCode, null, null, METADATA);

            assertThat(completion.returnPath()).isEqualTo("/editor");
            assertThat(completion.returnPath())
                    .doesNotContain(providerCode)
                    .doesNotContain(completion.grant().accessToken())
                    .doesNotContain(completion.grant().rawRefreshToken());
            assertThat(start.authorizationUrl().toString())
                    .doesNotContain(start.rawCorrelationToken())
                    .doesNotContain("test-secret");
            assertThat(appender.list.stream()
                            .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList())
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(providerCode)
                            .doesNotContain(start.rawCorrelationToken())
                            .doesNotContain(completion.grant().accessToken())
                            .doesNotContain(completion.grant().rawRefreshToken()));
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void trial_conversion_exactly_once() {
        String rawTrial = opaqueTokens.generate();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO trial_sessions(id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
                "trial_testvalue",
                opaqueTokens.hash(rawTrial),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
        emailAuthentication.register("trial@example.com", PASSWORD, null, rawTrial);
        SessionGrant grant = emailAuthentication.login("trial@example.com", PASSWORD, METADATA);

        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM trial_sessions WHERE id = 'trial_testvalue'", String.class))
                .isEqualTo(grant.user().user().id());
        Boolean convertedAgain = transactions.execute(status ->
                store.convertTrial(opaqueTokens.hash(rawTrial), grant.user().user().id(), Instant.now()));
        assertThat(convertedAgain).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM trial_sessions WHERE converted_at IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void trial_bootstrap_restores_counters_and_expiry_without_exposing_token() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            var created = mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.generationLimit").value(3))
                    .andExpect(jsonPath("$.generationsUsed").value(0))
                    .andExpect(jsonPath("$.remainingGenerations").value(3))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse();
            Cookie cookie = created.getCookie(cookies.trialCookieName());
            assertThat(cookie).isNotNull();
            assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
            assertThat(created.getHeader("Set-Cookie"))
                    .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
            assertThat(created.getContentAsString()).doesNotContain(cookie.getValue());
            String id = jdbc.queryForObject("select id from trial_sessions", String.class);
            Instant expiry = jdbc.queryForObject("select expires_at from trial_sessions", Timestamp.class).toInstant();
            assertThat(jdbc.queryForObject("select token_hash from trial_sessions", String.class))
                    .isEqualTo(opaqueTokens.hash(cookie.getValue()));
            for (int used : List.of(1, 3)) {
                jdbc.update("update trial_sessions set generations_used = ? where id = ?", used, id);
                for (var request : List.of(post("/api/v1/trial-session"), get("/api/v1/trial-session"))) {
                    mockMvc.perform(request.cookie(cookie).header("Origin", "https://ui.test"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(id))
                            .andExpect(jsonPath("$.status").value("active"))
                            .andExpect(jsonPath("$.generationsUsed").value(used))
                            .andExpect(jsonPath("$.remainingGenerations").value(3 - used))
                            .andExpect(header().doesNotExist("Set-Cookie"));
                }
            }
            assertThat(jdbc.queryForObject("select expires_at from trial_sessions", Timestamp.class).toInstant())
                    .isEqualTo(expiry);
            assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(1);
            assertThat(appender.list).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(cookie.getValue()));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void trial_missing_invalid_and_expired_credentials_never_create_replacement() throws Exception {
        mockMvc.perform(get("/api/v1/trial-session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TRIAL_SESSION_NOT_FOUND"));
        Cookie cookie = newTrialCookie();
        jdbc.update("update trial_sessions set expires_at = current_timestamp - interval '1 second'");
        for (Cookie credential : List.of(cookie, new Cookie(cookies.trialCookieName(), "invalid"),
                new Cookie(cookies.trialCookieName(), ""))) {
            for (var request : List.of(post("/api/v1/trial-session"), get("/api/v1/trial-session"))) {
                mockMvc.perform(request.cookie(credential).header("Origin", "https://ui.test"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("TRIAL_SESSION_NOT_FOUND"))
                        .andExpect(header().doesNotExist("Set-Cookie"));
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(1);
    }

    @Test
    void missing_route_returns_contract_error_instead_of_internal_error() throws Exception {
        SessionGrant grant = activeAccount("missing-route@example.com");

        mockMvc.perform(get("/api/v1/route-that-does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void trial_creation_is_rate_limited_across_requests_and_restore_still_works() throws Exception {
        Cookie first = newTrialCookie();
        for (int i = 1; i < 10; i++) {
            newTrialCookie();
        }
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER,
                            org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test").cookie(first))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select attempts from authentication_rate_limits where scope = 'TRIAL_CREATE'",
                Integer.class)).isEqualTo(12);
    }

    @Test
    void trial_bootstrap_requires_allowlisted_origin() throws Exception {
        mockMvc.perform(post("/api/v1/trial-session")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        Cookie cookie = newTrialCookie();
        mockMvc.perform(post("/api/v1/trial-session").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/trial-session").cookie(cookie).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(1);
    }

    @Test
    void bootstrap_conversion_uses_existing_registration_and_preserves_counters() throws Exception {
        Cookie cookie = newTrialCookie();
        String trialId = jdbc.queryForObject("select id from trial_sessions", String.class);
        jdbc.update("update trial_sessions set generations_used = 2");
        insertTrialWorkspace(trialId);
        String registration = """
                {"email":"bootstrap@example.com","password":"%s"}
                """.formatted(PASSWORD);
        mockMvc.perform(post("/api/v1/auth/register").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/register").cookie(cookie).header("Origin", "https://evil.test")
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select converted_at from trial_sessions", Timestamp.class)).isNull();
        mockMvc.perform(post("/api/v1/auth/register").cookie(cookie).header("Origin", "https://ui.test")
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isCreated())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(cookies.trialCookieName() + "="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"))));
        String userId = jdbc.queryForObject("select owner_user_id from trial_sessions", String.class);
        assertThat(userId).isNotBlank();
        assertThat(jdbc.queryForObject("select generations_used from trial_sessions", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select owner_user_id from products where id = 'prd_bootstrap'", String.class))
                .isEqualTo(userId);
        for (var request : List.of(post("/api/v1/trial-session"), get("/api/v1/trial-session"))) {
            mockMvc.perform(request.cookie(cookie).header("Origin", "https://ui.test"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TRIAL_SESSION_NOT_FOUND"));
        }
        var grant = emailAuthentication.login("bootstrap@example.com", PASSWORD, METADATA);
        mockMvc.perform(get("/api/v1/projects/prj_bootstrap").header("Authorization", "Bearer " + grant.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/register").cookie(cookie).header("Origin", "https://ui.test")
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(1);
    }

    @Test
    void bearer_has_priority_for_workspace_but_does_not_replace_trial_cookie() throws Exception {
        Cookie cookie = newTrialCookie();
        insertTrialWorkspace(jdbc.queryForObject("select id from trial_sessions", String.class));
        var grant = activeAccount("separate-owner@example.com");
        mockMvc.perform(get("/api/v1/projects/prj_bootstrap").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/prj_bootstrap").cookie(cookie)
                        .header("Authorization", "Bearer " + grant.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/prj_bootstrap").cookie(cookie)
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/trial-session").header("Authorization", "Bearer " + grant.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/trial-session").cookie(cookie)
                        .header("Authorization", "Bearer " + grant.accessToken()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select owner_user_id from trial_sessions", String.class)).isNull();
    }

    @Test
    void bearer_registration_does_not_convert_ambient_trial_owner() throws Exception {
        Cookie cookie = newTrialCookie();
        String trialId = jdbc.queryForObject("select id from trial_sessions", String.class);
        insertTrialWorkspace(trialId);
        var grant = activeAccount("existing-bearer@example.com");
        mockMvc.perform(post("/api/v1/auth/register").cookie(cookie)
                        .header("Authorization", "Bearer " + grant.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new-bearer-registration@example.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("select owner_user_id from trial_sessions", String.class)).isNull();
        assertThat(jdbc.queryForObject("select owner_trial_session_id from products", String.class)).isEqualTo(trialId);
        mockMvc.perform(get("/api/v1/trial-session").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void parallel_trial_creation_cannot_exceed_database_rate_limit() throws Exception {
        for (int i = 0; i < 9; i++) {
            newTrialCookie();
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> create = () -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start barrier timed out");
                }
                return mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(create);
            Future<Integer> second = executor.submit(create);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 429);
        }
        assertThat(jdbc.queryForObject("select count(*) from trial_sessions", Integer.class)).isEqualTo(10);
    }

    private Owner trialOwner(Cookie cookie) {
        return new Owner("trial", trialService.get(cookie.getValue()).id());
    }

    private Command billingCommand(String key,
            Code code, String subject) {
        return new Command("trial-test", key, 1, Map.of(1, "a".repeat(64)),
                code, subject, "trial-test", "job_" + java.util.UUID.randomUUID());
    }

    private OAuthCompletion completeOAuth(String rawTrial) {
        var start = yandexOAuth.start("/editor");
        String state = UriComponentsBuilder.fromUri(start.authorizationUrl()).build().getQueryParams().getFirst("state");
        return yandexOAuth.complete(start.rawCorrelationToken(), state, "test-code", null, rawTrial, METADATA);
    }

    @Test
    void trial_grant_is_once_under_parallel_bootstrap_and_preserves_configuration_snapshot() throws Exception {
        Cookie cookie = newTrialCookie();
        var owner = trialOwner(cookie);
        var initial = billing.wallet(owner);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i=0; i<8; i++) futures.add(executor.submit(() -> {
                assertThat(trialService.bootstrap(cookie.getValue(), "ignored-existing-cookie").session().wallet().id())
                        .isEqualTo(initial.id());
            }));
            for (var future : futures) future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(billing.wallet(owner).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='trial_grant'", Long.class)).isEqualTo(1);
        String version = "trial-snapshot-test";
        jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values(?,800,1,clock_timestamp(),'test')",version);
        jdbc.update("insert into billing_prices select ?,operation_code,amount_units from billing_prices where version_id='billing-2026-10-14-v1'",version);
        jdbc.update("update billing_price_versions set published_at=clock_timestamp() where id=?",version);
        jdbc.update("update billing_configuration set active_version=?",version);
        try {
            assertThat(trialService.get(cookie.getValue()).allowanceUnits()).isEqualTo(600);
            assertThat(trialService.bootstrap(null,"new-config-identity").session().allowanceUnits()).isEqualTo(800);
        } finally { jdbc.update("update billing_configuration set active_version='billing-2026-10-14-v1'"); }
    }

    @Test
    void email_conversion_keeps_pending_money_free_right_and_replay_with_no_registration_grant() throws Exception {
        Cookie cookie = newTrialCookie();
        var owner = trialOwner(cookie);
        var command = billingCommand("pending", Code.image_generation, null);
        var freeCommand = billingCommand("free", Code.image_entire_edit, "stable-card");
        var paid = transactions.execute(status -> billing.admit(owner,command).operation());
        var free = transactions.execute(status -> billing.admit(owner,freeCommand).operation());
        var user = emailAuthentication.register("pending-trial@example.com", PASSWORD, null, cookie.getValue());
        var registered = new Owner("user",user.user().id());
        assertThat(billing.wallet(registered).id()).isEqualTo(paid.walletId());
        assertThat(billing.wallet(registered).reservedUnits()).isEqualTo(400);
        assertThat(billing.wallet(registered).availableUnits()).isEqualTo(200);
        assertThat(transactions.execute(status -> billing.admit(registered,command)).operation().snapshot()).isEqualTo(paid.snapshot());
        assertThat(transactions.execute(status -> billing.admit(registered,freeCommand)).operation().snapshot()).isEqualTo(free.snapshot());
        assertThat(transactions.<Boolean>execute(status -> billing.finish(paid.snapshot().operationId(),0,
                Outcome.captured))).isTrue();
        transactions.execute(status -> billing.finish(free.snapshot().operationId(),0,
                Outcome.captured));
        emailAuthentication.login("pending-trial@example.com",PASSWORD,METADATA);
        assertThat(billing.wallet(registered).totalUnits()).isEqualTo(200);
        assertThat(billing.wallet(registered).reservedUnits()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='ai_debit'",Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> trialService.get(cookie.getValue())).isInstanceOf(AuthException.class);
        // A technical failure/retry returns the right only before a successful consume.
        var next = billingCommand("after-free",Code.image_entire_edit,"stable-card");
        assertThatThrownBy(() -> transactions.execute(status -> billing.admit(registered,next)))
                .isInstanceOf(BillingFailure.class);
    }

    @Test
    void oauth_conversion_to_new_user_retains_pending_operation() throws Exception {
        Cookie cookie = newTrialCookie(); var owner = trialOwner(cookie);
        var command = billingCommand("oauth-new",Code.image_generation,null);
        var op = transactions.execute(status -> billing.admit(owner,command).operation());
        var completion = completeOAuth(cookie.getValue());
        var user = new Owner("user",completion.grant().user().user().id());
        assertThat(jdbc.queryForObject("select completed_at is null from registration_onboarding where user_id=?",Boolean.class,user.id())).isTrue();
        assertThat(billing.wallet(user).id()).isEqualTo(op.walletId());
        transactions.execute(status -> billing.finish(op.snapshot().operationId(),0,Outcome.released));
        assertThat(billing.wallet(user).availableUnits()).isEqualTo(600);
        assertThat(transactions.execute(status -> billing.admit(user,command)).operation().state()).isEqualTo("released");
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(1);
    }

    @Test
    void oauth_merge_preserves_both_grants_history_pending_reserves_and_receipts() throws Exception {
        var existing = completeOAuth(null);
        var user = new Owner("user",existing.grant().user().user().id());
        Cookie cookie = newTrialCookie(); var trial = trialOwner(cookie);
        var userCommand = billingCommand("user-key",Code.image_generation,null);
        var trialCommand = billingCommand("trial-key",Code.image_region_edit,null);
        var first = transactions.execute(status -> billing.admit(user,userCommand).operation());
        var second = transactions.execute(status -> billing.admit(trial,trialCommand).operation());
        var before = jdbc.queryForList("select * from billing_ledger order by id");
        var converted = completeOAuth(cookie.getValue());
        assertThat(converted.grant().user().user().id()).isEqualTo(user.id());
        assertThat(billing.wallet(user).totalUnits()).isEqualTo(1200);
        assertThat(billing.wallet(user).reservedUnits()).isEqualTo(600);
        assertThat(jdbc.queryForList("select * from billing_ledger order by id")).isEqualTo(before);
        assertThat(transactions.execute(status -> billing.admit(user,userCommand)).operation().snapshot()).isEqualTo(first.snapshot());
        assertThat(transactions.execute(status -> billing.admit(user,trialCommand)).operation().snapshot()).isEqualTo(second.snapshot());
        transactions.execute(status -> billing.finish(first.snapshot().operationId(),0,Outcome.captured));
        transactions.execute(status -> billing.finish(second.snapshot().operationId(),0,Outcome.captured));
        assertThat(billing.wallet(user).totalUnits()).isEqualTo(600);
        assertThat(billing.wallet(user).usedUnits()).isEqualTo(600);
        assertThat(billing.wallet(user).reservedUnits()).isZero();
        var history = billing.history(user,new HistoryQuery(null,null,"oldest",10,null));
        assertThat(history.items()).hasSize(4);
        assertThat(history.items()).extracting(Entry::kind)
                .containsExactly("trial_grant","trial_grant","ai_debit","ai_debit");
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(2);
        store.initializeBilling(user.id());
        assertThat(billing.wallet(user).totalUnits()).isEqualTo(600);
    }

    @Test
    void existing_user_lazy_initialization_is_atomic_and_does_not_import_demo_balance() throws Exception {
        // Direct auth persistence represents an account predating this rollout.
        String id = "usr_legacy";
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values(?,'legacy@example.com',true,'ACTIVE',clock_timestamp(),clock_timestamp())",id);
        var owner = new Owner("user",id);
        assertThat(billing.wallet(owner).totalUnits()).isZero();
        try (var executor=Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(executor.submit(() -> store.initializeBilling(id)));
            for(var future:futures) future.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(billing.wallet(owner).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/billing/credits/wallet").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalUnits").value(600));
        assertThat(jdbc.queryForObject("select count(*) from users",Long.class)).isEqualTo(1);
    }

    @Test
    void invalid_trial_registration_rolls_back_user_and_financial_changes() {
        assertThatThrownBy(() -> emailAuthentication.register("invalid-trial@example.com",PASSWORD,null,"invalid"))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("select count(*) from users",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isZero();
    }

    @Test
    void parallel_conversion_to_different_users_has_exactly_one_winner() throws Exception {
        Cookie cookie = newTrialCookie();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var futures=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) { int n=i; futures.add(executor.submit(() -> {
                start.await();
                try { emailAuthentication.register("conversion-"+n+"@example.com",PASSWORD,null,cookie.getValue());return true; }
                catch(AuthException e) { assertThat(e.code()).isEqualTo(AuthErrorCode.TRIAL_SESSION_NOT_FOUND);return false; }
            })); }
            start.countDown();
            assertThat(List.of(futures.get(0).get(20,java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select count(*) from users",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(1);
    }

    @Test
    void exhausted_status_uses_total_not_reserved_or_legacy_counter() throws Exception {
        Cookie cookie=newTrialCookie();var owner=trialOwner(cookie);
        var generation=billingCommand("all-reserved",Code.image_generation,null);
        var edit=billingCommand("rest-reserved",Code.image_region_edit,null);
        var first=transactions.execute(status -> billing.admit(owner,generation).operation());
        var second=transactions.execute(status -> billing.admit(owner,edit).operation());
        mockMvc.perform(get("/api/v1/trial-session").cookie(cookie)).andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.allowanceUnits").value(600)).andExpect(jsonPath("$.wallet.availableUnits").value(0));
        transactions.execute(status -> billing.finish(first.snapshot().operationId(),0,Outcome.captured));
        transactions.execute(status -> billing.finish(second.snapshot().operationId(),0,Outcome.captured));
        mockMvc.perform(get("/api/v1/trial-session").cookie(cookie)).andExpect(jsonPath("$.status").value("exhausted"));
        String debit=jdbc.queryForObject("select id from billing_ledger where operation_id=?",String.class,first.snapshot().operationId());
        transactions.execute(status -> billing.compensate(first.walletId(),debit,"manual-correction",100));
        mockMvc.perform(get("/api/v1/trial-session").cookie(cookie)).andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void already_converted_legacy_trial_receives_only_one_grant_and_keeps_its_identity_alias() {
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values('usr_previous','previous@example.com',true,'ACTIVE',clock_timestamp(),clock_timestamp())");
        jdbc.update("insert into trial_sessions(id,token_hash,owner_user_id,converted_at,created_at,expires_at) values('trial_previous',?,'usr_previous',clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 day')",opaqueTokens.hash("old-cookie"));
        var user=new Owner("user","usr_previous");
        store.initializeBilling(user.id());store.initializeBilling(user.id());
        assertThat(billing.wallet(user).totalUnits()).isEqualTo(600);
        assertThat(billing.wallet(new Owner("trial","trial_previous")).id()).isEqualTo(billing.wallet(user).id());
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> trialService.get("old-cookie")).isInstanceOf(AuthException.class);
    }

    @Test
    void workspace_conversion_failure_rolls_back_auth_financial_alias_and_reserve() throws Exception {
        Cookie cookie=newTrialCookie();var owner=trialOwner(cookie);insertTrialWorkspace(owner.id());
        var cmd=billingCommand("rollback",Code.image_generation,null);
        var op=transactions.execute(status -> billing.admit(owner,cmd).operation());
        jdbc.execute("create function test_reject_trial_transfer() returns trigger language plpgsql as $$ begin raise exception 'test transfer failure'; end $$");
        jdbc.execute("create trigger test_reject_transfer before update on products for each row execute function test_reject_trial_transfer()");
        try {
            assertThatThrownBy(() -> emailAuthentication.register("rollback@example.com",PASSWORD,null,cookie.getValue()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("drop trigger test_reject_transfer on products");jdbc.execute("drop function test_reject_trial_transfer()");
        }
        assertThat(jdbc.queryForObject("select count(*) from users",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select owner_user_id from trial_sessions",String.class)).isNull();
        assertThat(jdbc.queryForObject("select owner_trial_session_id from products",String.class)).isEqualTo(owner.id());
        assertThat(billing.wallet(owner).id()).isEqualTo(op.walletId());
        assertThat(billing.wallet(owner).reservedUnits()).isEqualTo(400);
        assertThat(billing.wallet(owner).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from billing_owners where kind='user'",Long.class)).isZero();
    }

    private com.tovarika.tech.cards.CardGenerationJob seedClaimedGeneration(String trialId) {
        insertTrialWorkspace(trialId);
        Instant now=Instant.now();
        var job=new com.tovarika.tech.cards.CardGenerationJob("job_conversion","prj_bootstrap","card_conversion",
                "queued",null,0,now,null,null);
        transactions.execute(status -> {
            generationStore.enqueue(job,1,null,null,"3:4","sample prompt",null,null,null,null,null,1,
                    "product description",trialId,"generation-replay",now);
            return null;
        });
        return transactions.execute(status -> generationStore.claim(now,now.plusSeconds(300)).orElseThrow());
    }

    private void completeSeedGeneration(com.tovarika.tech.cards.CardGenerationJob job) {
        transactions.execute(status -> generationStore.complete(job,
                new com.tovarika.tech.cards.CardGenerationStore.GeneratedAsset("asset_"+java.util.UUID.randomUUID().toString().replace("-",""),"test-key-"+java.util.UUID.randomUUID(),"image/png",1,100,100),Instant.now()));
    }

    @Test
    void actual_generation_job_finishes_and_replays_after_conversion_in_same_financial_transaction() throws Exception {
        Cookie cookie=newTrialCookie();var owner=trialOwner(cookie);var job=seedClaimedGeneration(owner.id());
        var cmd=new Command("cards","generation-replay",1,Map.of(1,"a".repeat(64)),Code.image_generation,null,"card_generation",job.id());
        var op=transactions.execute(status -> billing.admit(owner,cmd).operation());
        long fence=transactions.execute(status -> billing.claim(op.snapshot().operationId(),0));
        var user=emailAuthentication.register("actual-generation@example.com",PASSWORD,null,cookie.getValue());
        String userId=user.user().id();
        assertThat(generationService.start("prj_bootstrap","generation-replay",userId,null,null,"sample prompt","3:4",null,null).id()).isEqualTo(job.id());
        Boolean applied=transactions.execute(status -> {
            if(!billing.finish(op.snapshot().operationId(),fence,Outcome.captured)) throw new IllegalStateException("Stale financial fence");
            if(!generationStore.complete(job,new com.tovarika.tech.cards.CardGenerationStore.GeneratedAsset(
                    "asset_"+java.util.UUID.randomUUID().toString().replace("-",""),"test-key-"+java.util.UUID.randomUUID(),"image/png",1,100,100),Instant.now())) throw new IllegalStateException("Stale job fence");
            return true;
        });
        assertThat(applied).isTrue();
        assertThat(generationService.start("prj_bootstrap","generation-replay",userId,null,null,"sample prompt","3:4",null,null).status()).isEqualTo("completed");
        assertThat(billing.wallet(new Owner("user",userId)).totalUnits()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from project_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from cards",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='ai_debit'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select owner_scope from project_jobs",String.class)).isEqualTo(owner.id());
    }

    @Test
    void actual_image_edit_replays_after_conversion_and_retry_recovers_free_right_after_failure() throws Exception {
        Cookie cookie=newTrialCookie();var trial=trialOwner(cookie);var generation=seedClaimedGeneration(trial.id());completeSeedGeneration(generation);
        String base=jdbc.queryForObject("select current_version_id from cards",String.class);
        var edit=new com.tovarika.tech.cards.editing.domain.ImageEdit("entire","edit prompt",null,null,null,null);
        String jobId=editingService.start("prj_bootstrap","card_conversion","edit-replay",null,trial.id(),base,1L,edit,false);
        var cmd=new Command("image-edit","edit-replay",1,Map.of(1,"a".repeat(64)),Code.image_entire_edit,"card_conversion","image-edit",jobId);
        var op=transactions.execute(status -> billing.admit(trial,cmd).operation());
        var claimed=transactions.execute(status -> editingStore.claim(Instant.now()).orElseThrow());
        var user=emailAuthentication.register("actual-edit@example.com",PASSWORD,null,cookie.getValue());String userId=user.user().id();
        assertThat(editingService.start("prj_bootstrap","card_conversion","edit-replay",userId,null,base,1L,edit,false)).isEqualTo(jobId);
        transactions.execute(status -> {
            billing.finish(op.snapshot().operationId(),0,Outcome.released);
            if(!editingStore.fail(claimed,"AI_OPERATION_FAILED",Instant.now())) throw new IllegalStateException("Stale edit job");
            return null;
        });
        assertThat(editingService.start("prj_bootstrap","card_conversion","edit-replay",userId,null,base,1L,edit,false)).isEqualTo(jobId);
        String retry=editingService.start("prj_bootstrap","card_conversion","edit-new-retry",userId,null,base,1L,edit,false);
        var newCommand=new Command("image-edit","edit-new-retry",1,Map.of(1,"b".repeat(64)),Code.image_entire_edit,"card_conversion","image-edit",retry);
        assertThat(transactions.execute(status -> billing.admit(new Owner("user",userId),newCommand)).operation().snapshot().funding()).isEqualTo(Funding.free_entitlement);
        assertThat(billing.wallet(new Owner("user",userId)).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from project_jobs where type='card_image_edit'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='ai_debit'",Long.class)).isZero();
    }

    @Test
    void actual_analysis_job_stays_free_and_replays_after_conversion() throws Exception {
        Cookie cookie=newTrialCookie();var trial=trialOwner(cookie);insertTrialWorkspace(trial.id());
        var job=analysisService.start("prd_bootstrap","analysis-replay",null,trial.id());
        var claimed=transactions.execute(status -> analysisStore.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow());
        var user=emailAuthentication.register("actual-analysis@example.com",PASSWORD,null,cookie.getValue());String userId=user.user().id();
        assertThat(analysisService.start("prd_bootstrap","analysis-replay",userId,null).id()).isEqualTo(job.id());
        transactions.execute(status -> analysisStore.complete(claimed,
                new com.tovarika.tech.analyses.domain.AnalysisResult("Product","Description","Idea"),"sample prompt",Instant.now()));
        assertThat(analysisService.start("prd_bootstrap","analysis-replay",userId,null).status()).isEqualTo("completed");
        assertThat(billing.wallet(new Owner("user",userId)).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from analysis_jobs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='ai_debit'",Long.class)).isZero();
    }

    @Test
    void oauth_merge_keeps_actual_generation_keys_in_their_original_project_namespaces() throws Exception {
        var existing=completeOAuth(null);String userId=existing.grant().user().user().id();
        jdbc.update("insert into products(id,name,owner_user_id,created_at,updated_at) values('prd_existing','Existing',?,clock_timestamp(),clock_timestamp())",userId);
        jdbc.update("insert into projects(id,name,product_id,default_aspect_ratio,created_at,updated_at) values('prj_existing','Existing','prd_existing','3:4',clock_timestamp(),clock_timestamp())");
        Instant now=Instant.now();
        var userJob=new com.tovarika.tech.cards.CardGenerationJob("job_existing","prj_existing","card_existing","queued",null,0,now,null,null);
        transactions.execute(status -> {
            generationStore.enqueue(userJob,1,null,null,"3:4","sample prompt",null,null,null,null,null,1,"description",userId,"generation-replay",now);
            return null;
        });
        jdbc.update("update project_jobs set status='processing',attempt=1,lease_until=clock_timestamp()+interval '5 minutes' where id=?",userJob.id());
        Cookie cookie=newTrialCookie();var trial=trialOwner(cookie);seedClaimedGeneration(trial.id());
        completeOAuth(cookie.getValue());
        assertThat(generationService.start("prj_existing","generation-replay",userId,null,null,"sample prompt","3:4",null,null).id()).isEqualTo(userJob.id());
        assertThat(generationService.start("prj_bootstrap","generation-replay",userId,null,null,"sample prompt","3:4",null,null).id()).isEqualTo("job_conversion");
        assertThat(jdbc.queryForObject("select count(*) from project_jobs",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct owner_scope) from project_jobs",Long.class)).isEqualTo(2);
        assertThat(billing.wallet(new Owner("user",userId)).totalUnits()).isEqualTo(1200);
    }

    private Cookie newTrialCookie() throws Exception {
        return mockMvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(cookies.trialCookieName());
    }

    private void insertTrialWorkspace(String trialId) {
        jdbc.update("""
                insert into products(id, name, owner_trial_session_id, created_at, updated_at)
                values ('prd_bootstrap', 'Trial product', ?, current_timestamp, current_timestamp)
                """, trialId);
        jdbc.update("""
                insert into projects(id, name, product_id, default_aspect_ratio, created_at, updated_at)
                values ('prj_bootstrap', 'Trial project', 'prd_bootstrap', '3:4', current_timestamp, current_timestamp)
                """);
    }

    private SessionGrant activeAccount(String email) {
        emailAuthentication.register(email, PASSWORD, "Test User", null);
        return emailAuthentication.login(email, PASSWORD, METADATA);
    }

    private AuthenticationSession session(String id) {
        return store.findSession(id).orElseThrow();
    }

    private void assertSessionRevoked(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuthException.class, error ->
                assertThat(error.code()).isEqualTo(AuthErrorCode.SESSION_REVOKED));
    }

    private String queryParameter(java.net.URI uri, String name) {
        String value = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
        return value == null ? null : org.springframework.web.util.UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    private SecretKey signingKey() {
        return new SecretKeySpec(
                Base64.getDecoder().decode(properties.jwt().secretBase64()), "HmacSHA256");
    }

    private String customJwt(
            SessionGrant grant,
            String issuer,
            String audience,
            Instant issuedAt,
            Instant expiresAt,
            SecretKey key) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(grant.user().user().id())
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id("test-jti")
                .claim("sid", grant.sessionId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @TestConfiguration
    static class TestDoubles {
        @Bean
        @Primary
        CapturingMessageSender capturingMessageSender() {
            return new CapturingMessageSender();
        }

        @Bean
        @Primary
        BreachedPasswordChecker testBreachedPasswordChecker() {
            return password -> false;
        }

        @Bean
        @Primary
        FakeYandexOAuthClient fakeYandexOAuthClient() {
            return new FakeYandexOAuthClient();
        }
    }

    static final class CapturingMessageSender implements AuthenticationMessageSender {
        private final Map<String, String> resets = new ConcurrentHashMap<>();

        @Override
        public void sendPasswordReset(String email, String rawToken) {
            resets.put(email, rawToken);
        }

        String resetToken(String email) {
            return resets.get(email);
        }

        void clear() {
            resets.clear();
        }
    }

    static final class FakeYandexOAuthClient implements YandexOAuthClient {
        volatile boolean rejectPkce;

        @Override
        public YandexProfile exchangeCode(String code, String pkceVerifier) {
            if (rejectPkce) {
                throw AuthException.badRequest(AuthErrorCode.OAUTH_PROVIDER_ERROR, "PKCE validation failed");
            }
            assertThat(code).isNotBlank();
            assertThat(pkceVerifier).hasSizeBetween(43, 128);
            return new YandexProfile("yandex-test-user", "yandex@example.com", "Yandex User");
        }

        void reset() {
            rejectPkce = false;
        }
    }
}
