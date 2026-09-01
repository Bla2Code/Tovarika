package com.tovarika.tech.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.EmailNormalizer;
import com.tovarika.tech.auth.application.port.YandexOAuthClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class YandexOAuthRestClient implements YandexOAuthClient {

    private final AuthenticationProperties properties;
    private final RestClient restClient;

    public YandexOAuthRestClient(AuthenticationProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public YandexProfile exchangeCode(String code, String pkceVerifier) {
        AuthenticationProperties.OAuth.Yandex yandex = properties.oauth().yandex();
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", yandex.redirectUri().toString());
            form.add("code_verifier", pkceVerifier);
            TokenResponse token = restClient
                    .post()
                    .uri(yandex.tokenUri())
                    .headers(headers -> headers.setBasicAuth(yandex.clientId(), yandex.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw providerFailure();
            }
            ProfileResponse profile = restClient
                    .get()
                    .uri(yandex.userInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "OAuth " + token.accessToken())
                    .retrieve()
                    .body(ProfileResponse.class);
            if (profile == null
                    || !yandex.clientId().equals(profile.clientId())
                    || profile.id() == null
                    || profile.defaultEmail() == null) {
                throw providerFailure();
            }
            return new YandexProfile(
                    profile.id(), EmailNormalizer.normalize(profile.defaultEmail()), profile.displayName());
        } catch (RestClientException providerException) {
            throw providerFailure();
        }
    }

    private AuthException providerFailure() {
        return AuthException.badRequest(AuthErrorCode.OAUTH_PROVIDER_ERROR, "Yandex OAuth validation failed");
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {}

    private record ProfileResponse(
            String id,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("default_email") String defaultEmail,
            @JsonProperty("display_name") String displayName) {}
}
