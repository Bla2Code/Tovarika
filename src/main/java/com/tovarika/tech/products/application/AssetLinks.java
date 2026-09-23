package com.tovarika.tech.products.application;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.shared.application.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Service
@EnableConfigurationProperties(AssetLinks.Properties.class)
public class AssetLinks {
    private final byte[] key;
    private final Clock clock;
    private final Properties properties;
    public AssetLinks(AuthenticationProperties auth, Clock clock, Properties properties) {
        this.key = Base64.getDecoder().decode(auth.jwt().secretBase64()); this.clock=clock; this.properties=properties;
    }
    public Link create(String id) {
        long expiry = clock.instant().plusSeconds(900).getEpochSecond();
        return new Link(properties.publicBaseUrl().replaceAll("/$", "") + "/media/assets/" + id
                + "?expires=" + expiry + "&signature=" + signature(id, expiry), Instant.ofEpochSecond(expiry));
    }
    public void verify(String id, long expires, String signature) {
        if (expires <= clock.instant().getEpochSecond() || expires > clock.instant().plusSeconds(900).getEpochSecond()
                || signature == null || !MessageDigest.isEqual(signature(id, expires).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiFailure(403, "FORBIDDEN", "Asset link is invalid or expired");
        }
    }
    private String signature(String id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("asset-download\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("HMAC unavailable"); }
    }
    public record Link(String url, Instant expiresAt) {}
    @ConfigurationProperties("tovarika.assets")
    public record Properties(@DefaultValue("http://localhost:8080") String publicBaseUrl) {}
}
