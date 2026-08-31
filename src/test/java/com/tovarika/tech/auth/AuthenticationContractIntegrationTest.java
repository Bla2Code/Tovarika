package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE TABLE authentication_rate_limits, oauth_attempts, password_reset_tokens,
                    refresh_tokens, auth_sessions, auth_identities, trial_sessions, users CASCADE
                """);
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
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
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
