package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.port.BreachedPasswordChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PwnedPasswordChecker implements BreachedPasswordChecker {

    private final AuthenticationProperties properties;
    private final RestClient restClient;

    public PwnedPasswordChecker(AuthenticationProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public boolean isCompromised(String password) {
        if (!properties.password().breachedCheckEnabled()) {
            return false;
        }
        String digest = sha1(password);
        String response = restClient
                .get()
                .uri(properties.password().pwnedApiUrl().resolve(digest.substring(0, 5)))
                .accept(MediaType.TEXT_PLAIN)
                .header("Add-Padding", "true")
                .retrieve()
                .body(String.class);
        if (response == null) {
            throw new IllegalStateException("Breached password service returned an empty response");
        }
        String suffix = digest.substring(5);
        return response.lines().anyMatch(line -> line.regionMatches(true, 0, suffix, 0, suffix.length()));
    }

    private String sha1(String password) {
        try {
            return HexFormat.of()
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
    }
}
