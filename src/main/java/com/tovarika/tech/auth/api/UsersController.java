package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.UsersApi;
import com.tovarika.api.publicapi.model.UpdateMeRequestDto;
import com.tovarika.api.publicapi.model.UserDto;
import com.tovarika.api.publicapi.model.OnboardingAnswersDto;
import com.tovarika.api.publicapi.model.OnboardingStatusDto;
import com.tovarika.tech.auth.application.RegistrationOnboardingService;
import com.tovarika.tech.auth.application.UserAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsersController implements UsersApi {

    private final UserAccountService users;
    private final RequestAuthenticationContext requestContext;
    private final AuthenticationDtoMapper mapper;
    private final RegistrationOnboardingService onboarding;

    public UsersController(
            UserAccountService users,
            RequestAuthenticationContext requestContext,
            AuthenticationDtoMapper mapper,
            RegistrationOnboardingService onboarding) {
        this.users = users;
        this.requestContext = requestContext;
        this.mapper = mapper;
        this.onboarding = onboarding;
    }

    @Override
    public ResponseEntity<OnboardingStatusDto> getOnboarding() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new OnboardingStatusDto(onboarding.completed(requestContext.principal().userId())));
    }

    @Override
    public ResponseEntity<OnboardingStatusDto> completeOnboarding(OnboardingAnswersDto request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new OnboardingStatusDto(onboarding.complete(requestContext.principal().userId(),
                        request.getRole().getValue(), request.getFrequency().getValue(), request.getDifficulty().getValue())));
    }

    @Override
    public ResponseEntity<UserDto> getCurrentUser() {
        return ResponseEntity.ok(mapper.user(users.get(requestContext.principal().userId())));
    }

    @Override
    public ResponseEntity<UserDto> updateCurrentUser(UpdateMeRequestDto request) {
        return ResponseEntity.ok(mapper.user(users.updateDisplayName(
                requestContext.principal().userId(), request.getDisplayName())));
    }
}
