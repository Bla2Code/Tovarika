package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.UsersApi;
import com.tovarika.api.publicapi.model.UpdateMeRequestDto;
import com.tovarika.api.publicapi.model.UserDto;
import com.tovarika.tech.auth.application.UserAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsersController implements UsersApi {

    private final UserAccountService users;
    private final RequestAuthenticationContext requestContext;
    private final AuthenticationDtoMapper mapper;

    public UsersController(
            UserAccountService users,
            RequestAuthenticationContext requestContext,
            AuthenticationDtoMapper mapper) {
        this.users = users;
        this.requestContext = requestContext;
        this.mapper = mapper;
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
