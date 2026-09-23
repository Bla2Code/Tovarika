package com.tovarika.tech.trial.api;

import com.tovarika.api.publicapi.TrialApi;
import com.tovarika.api.publicapi.model.TrialSessionDto;
import com.tovarika.api.publicapi.model.TrialStatusDto;
import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.trial.application.TrialSessionService;
import com.tovarika.tech.trial.domain.TrialSession;
import java.time.Duration;
import java.time.ZoneOffset;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrialController implements TrialApi {
    private final TrialSessionService sessions;
    private final RequestAuthenticationContext request;
    private final AuthenticationCookieService cookies;

    public TrialController(TrialSessionService sessions, RequestAuthenticationContext request,
            AuthenticationCookieService cookies) {
        this.sessions = sessions;
        this.request = request;
        this.cookies = cookies;
    }

    @Override
    public ResponseEntity<TrialSessionDto> createTrialSession() {
        var result = sessions.bootstrap(request.cookie(cookies.trialCookieName()), request.rateLimitSubject("trial"));
        var response = ResponseEntity.status(result.rawToken() == null ? 200 : 201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (result.rawToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookies.createTrial(result.rawToken(),
                    Duration.between(result.session().createdAt(), result.session().expiresAt())).toString());
        }
        return response.body(dto(result.session()));
    }

    @Override
    public ResponseEntity<TrialSessionDto> getTrialSession() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(dto(sessions.get(request.cookie(cookies.trialCookieName()))));
    }

    private TrialSessionDto dto(TrialSession session) {
        return new TrialSessionDto(session.id(), session.exhausted() ? TrialStatusDto.EXHAUSTED : TrialStatusDto.ACTIVE,
                session.generationLimit(), session.generationsUsed(), session.remainingGenerations(),
                session.createdAt().atOffset(ZoneOffset.UTC), session.expiresAt().atOffset(ZoneOffset.UTC));
    }
}
