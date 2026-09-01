package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.AuthenticationApi;
import com.tovarika.api.publicapi.model.AccessTokenDto;
import com.tovarika.api.publicapi.model.AuthSessionCollectionDto;
import com.tovarika.api.publicapi.model.ChangePasswordRequestDto;
import com.tovarika.api.publicapi.model.ConfirmPasswordResetRequestDto;
import com.tovarika.api.publicapi.model.EmailCommandRequestDto;
import com.tovarika.api.publicapi.model.LoginRequestDto;
import com.tovarika.api.publicapi.model.OAuthAuthorizationDto;
import com.tovarika.api.publicapi.model.RegisterRequestDto;
import com.tovarika.api.publicapi.model.RegistrationResultDto;
import com.tovarika.api.publicapi.model.StartOAuthRequestDto;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.EmailAuthenticationService;
import com.tovarika.tech.auth.application.EmailNormalizer;
import com.tovarika.tech.auth.application.OAuthCompletion;
import com.tovarika.tech.auth.application.OAuthStart;
import com.tovarika.tech.auth.application.SessionGrant;
import com.tovarika.tech.auth.application.SessionService;
import com.tovarika.tech.auth.application.YandexOAuthService;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter;
import com.tovarika.tech.auth.application.port.AuthenticationRateLimiter.Scope;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController implements AuthenticationApi {

    private final EmailAuthenticationService emailAuthentication;
    private final SessionService sessions;
    private final YandexOAuthService yandexOAuth;
    private final AuthenticationCookieService cookies;
    private final RequestAuthenticationContext requestContext;
    private final AuthenticationDtoMapper mapper;
    private final AuthenticationRateLimiter rateLimiter;
    private final AuthenticationProperties properties;

    public AuthenticationController(
            EmailAuthenticationService emailAuthentication,
            SessionService sessions,
            YandexOAuthService yandexOAuth,
            AuthenticationCookieService cookies,
            RequestAuthenticationContext requestContext,
            AuthenticationDtoMapper mapper,
            AuthenticationRateLimiter rateLimiter,
            AuthenticationProperties properties) {
        this.emailAuthentication = emailAuthentication;
        this.sessions = sessions;
        this.yandexOAuth = yandexOAuth;
        this.cookies = cookies;
        this.requestContext = requestContext;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<RegistrationResultDto> registerWithEmail(RegisterRequestDto request) {
        rateLimiter.check(
                Scope.REGISTER,
                requestContext.rateLimitSubject(EmailNormalizer.normalize(request.getEmail())),
                properties.rateLimit().register());
        var user = emailAuthentication.register(
                request.getEmail(),
                request.getPassword(),
                request.getDisplayName(),
                requestContext.cookie(cookies.trialCookieName()));
        return ResponseEntity.status(201).body(new RegistrationResultDto(mapper.user(user)));
    }

    @Override
    public ResponseEntity<AccessTokenDto> loginWithEmail(LoginRequestDto request) {
        rateLimiter.check(
                Scope.LOGIN,
                requestContext.rateLimitSubject(EmailNormalizer.normalize(request.getEmail())),
                properties.rateLimit().login());
        return tokenResponse(emailAuthentication.login(
                request.getEmail(), request.getPassword(), requestContext.metadata()));
    }

    @Override
    public ResponseEntity<AccessTokenDto> refreshAccessToken() {
        rateLimiter.check(
                Scope.REFRESH, requestContext.rateLimitSubject("refresh"), properties.rateLimit().refresh());
        return tokenResponse(sessions.rotate(
                requestContext.cookie(cookies.refreshCookieName()), requestContext.metadata()));
    }

    @Override
    public ResponseEntity<Void> logout() {
        sessions.logout(requestContext.cookie(cookies.refreshCookieName()));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                .build();
    }

    @Override
    public ResponseEntity<Void> logoutAllSessions() {
        sessions.logoutAll(requestContext.principal().userId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString())
                .build();
    }

    @Override
    public ResponseEntity<Void> requestPasswordReset(EmailCommandRequestDto request) {
        rateLimiter.check(
                Scope.PASSWORD_RESET,
                requestContext.rateLimitSubject(EmailNormalizer.normalize(request.getEmail())),
                properties.rateLimit().passwordReset());
        emailAuthentication.requestPasswordReset(request.getEmail());
        return ResponseEntity.accepted().build();
    }

    @Override
    public ResponseEntity<Void> confirmPasswordReset(ConfirmPasswordResetRequestDto request) {
        rateLimiter.check(
                Scope.PASSWORD_RESET,
                requestContext.rateLimitSubject("confirmation"),
                properties.rateLimit().passwordReset());
        emailAuthentication.confirmPasswordReset(request.getToken(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AccessTokenDto> changePassword(ChangePasswordRequestDto request) {
        RequestAuthenticationContext.Principal principal = requestContext.principal();
        SessionGrant grant = emailAuthentication.changePassword(
                principal.userId(),
                principal.sessionId(),
                requestContext.cookie(cookies.refreshCookieName()),
                request.getCurrentPassword(),
                request.getNewPassword(),
                requestContext.metadata());
        return tokenResponse(grant);
    }

    @Override
    public ResponseEntity<OAuthAuthorizationDto> startYandexOAuth(@Nullable @Valid StartOAuthRequestDto request) {
        rateLimiter.check(
                Scope.OAUTH_START,
                requestContext.rateLimitSubject("yandex"),
                properties.rateLimit().oauthStart());
        OAuthStart start = yandexOAuth.start(request == null ? null : request.getReturnPath());
        OAuthAuthorizationDto body = new OAuthAuthorizationDto(
                start.authorizationUrl(), OffsetDateTime.ofInstant(start.expiresAt(), ZoneOffset.UTC));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.createOAuthCorrelation(start.rawCorrelationToken()).toString())
                .body(body);
    }

    @Override
    public ResponseEntity<Void> completeYandexOAuth(
            @Nullable String code,
            @Nullable String state,
            @Nullable String error,
            @Nullable String errorDescription) {
        rateLimiter.check(
                Scope.OAUTH_CALLBACK,
                requestContext.rateLimitSubject("yandex"),
                properties.rateLimit().oauthCallback());
        OAuthCompletion completion = yandexOAuth.complete(
                requestContext.cookie(cookies.oauthCookieName()),
                state,
                code,
                error,
                requestContext.cookie(cookies.trialCookieName()),
                requestContext.metadata());
        return ResponseEntity.status(303)
                .location(URI.create(completion.returnPath()))
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.createRefresh(completion.grant().rawRefreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearOAuthCorrelation().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearTrial().toString())
                .build();
    }

    @Override
    public ResponseEntity<AuthSessionCollectionDto> listAuthSessions() {
        RequestAuthenticationContext.Principal principal = requestContext.principal();
        return ResponseEntity.ok(new AuthSessionCollectionDto(sessions.list(principal.userId()).stream()
                .map(session -> mapper.session(session, principal.sessionId()))
                .toList()));
    }

    @Override
    public ResponseEntity<Void> revokeAuthSession(String sessionId) {
        sessions.revoke(requestContext.principal().userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AccessTokenDto> tokenResponse(SessionGrant grant) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.createRefresh(grant.rawRefreshToken()).toString())
                .body(mapper.accessToken(grant));
    }
}
