package com.tovarika.tech.project;

import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import org.springframework.stereotype.Component;

@Component
class ProjectRequestOwnerResolver {
    private final RequestAuthenticationContext request;
    private final WorkspaceIdentityResolver identities;

    ProjectRequestOwnerResolver(RequestAuthenticationContext request,
            WorkspaceIdentityResolver identities) {
        this.request = request;
        this.identities = identities;
    }

    ProjectOwner resolve() {
        var identity = identities.resolve();
        return new ProjectOwner(identity.userId(), identity.trialSessionId());
    }

    String resolveRegisteredUser() {
        RequestAuthenticationContext.Principal principal = request.optionalPrincipal();
        if (principal == null) {
            throw ProjectException.unauthorized(ErrorCodeDto.AUTHENTICATION_REQUIRED, "Authentication is required");
        }
        return principal.userId();
    }
}
