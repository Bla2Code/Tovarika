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
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.AuthErrorCode;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

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
                requestContext.optionalPrincipal() == null ? requestContext.cookie(cookies.trialCookieName()) : null);
        var response = ResponseEntity.status(201);
        if (requestContext.optionalPrincipal() == null && requestContext.cookie(cookies.trialCookieName()) != null) {
            response.header(HttpHeaders.SET_COOKIE, cookies.clearTrial().toString());
        }
        return response.body(new RegistrationResultDto(mapper.user(user)));
    }

    @Override
    public ResponseEntity<AccessTokenDto> loginWithEmail(LoginRequestDto request) {
        rateLimiter.check(
                Scope.LOGIN,
                requestContext.rateLimitSubject(EmailNormalizer.normalize(request.getEmail())),
                properties.rateLimit().login());
        String trialToken = requestContext.optionalPrincipal() == null
                ? requestContext.cookie(cookies.trialCookieName()) : null;
        return tokenResponse(emailAuthentication.login(
                request.getEmail(), request.getPassword(), requestContext.metadata(), trialToken), trialToken != null);
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
        String returnPath = "/editor";
        try {
            rateLimiter.check(
                    Scope.OAUTH_CALLBACK,
                    requestContext.rateLimitSubject("yandex"),
                    properties.rateLimit().oauthCallback());
            String correlation = requestContext.cookie(cookies.oauthCookieName());
            returnPath = yandexOAuth.returnPathForAttempt(correlation, state);
            OAuthCompletion completion = yandexOAuth.complete(
                    correlation, state, code, error,
                    requestContext.cookie(cookies.trialCookieName()), requestContext.metadata());
            return callbackResponse(completion.returnPath(), null)
                    .header(HttpHeaders.SET_COOKIE, cookies.createRefresh(completion.grant().rawRefreshToken()).toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearTrial().toString())
                    .build();
        } catch (AuthException failure) {
            String failureCode = failure.code() == AuthErrorCode.OAUTH_PROVIDER_ERROR && "access_denied".equals(error)
                    ? "access_denied" : failure.code().name();
            var response = callbackResponse(returnPath, failureCode);
            if (failure.code() == AuthErrorCode.TRIAL_SESSION_NOT_FOUND) {
                response.header(HttpHeaders.SET_COOKIE, cookies.clearTrial().toString());
            }
            return response.build();
        } catch (RuntimeException failure) {
            // Provider/DB exception messages can contain credentials. Never put them in URLs or logs.
            return callbackResponse(returnPath, AuthErrorCode.INTERNAL_ERROR.name()).build();
        }
    }

    private ResponseEntity.BodyBuilder callbackResponse(String returnPath, @Nullable String error) {
        var location = UriComponentsBuilder.fromPath("/auth/yandex/callback")
                .queryParam("return", returnPath);
        if (error != null) location.queryParam("error", error);
        return ResponseEntity.status(303)
                .location(location.build().encode().toUri())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.SET_COOKIE, cookies.clearOAuthCorrelation().toString());
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
        return tokenResponse(grant, false);
    }

    private ResponseEntity<AccessTokenDto> tokenResponse(SessionGrant grant, boolean clearTrial) {
        var response = ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.createRefresh(grant.rawRefreshToken()).toString());
        if (clearTrial) response.header(HttpHeaders.SET_COOKIE, cookies.clearTrial().toString());
        return response.body(mapper.accessToken(grant));
    }
}
