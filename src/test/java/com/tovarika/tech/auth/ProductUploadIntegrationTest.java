package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.auth.application.*;
import com.tovarika.tech.products.application.ProductStorage;
import com.tovarika.tech.products.application.ProductService;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false",
    "tovarika.security.cors.allowed-origins=https://ui.test"
})
@Import({AuthenticationContractIntegrationTest.TestDoubles.class, ProductUploadIntegrationTest.StorageConfig.class})
class ProductUploadIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailAuthenticationService auth;
    @Autowired FakeStorage storage;
    @Autowired ProductService products;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    MockMvc mvc;
    @BeforeEach void setup() {
        jdbc.execute("truncate users, trial_sessions, assets, products, authentication_rate_limits, product_uploads cascade");
        storage.data.clear(); storage.failPut=false; storage.failDelete=false;
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void allFormatsAreStoredUnchangedWithSignedUrlsAndSafeNames() throws Exception {
        Cookie cookie=trial();
        for (String format : List.of("png", "jpeg", "webp")) {
            byte[] original=fixture(format);
            var response=mvc.perform(multipart("/api/v1/products")
                    .file(new MockMultipartFile("image", "../../Товар."+format, "text/plain", original))
                    .cookie(cookie).header("Origin", "https://ui.test"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Товар"))
                    .andExpect(jsonPath("$.status").value("uploaded"))
                    .andExpect(jsonPath("$.sourceImage.purpose").value("source_image"))
                    .andExpect(jsonPath("$.sourceImage.mediaType").value("image/"+format))
                    .andExpect(jsonPath("$.sourceImage.width").value(4)).andReturn().getResponse();
            var body=json.readTree(response.getContentAsString());
            String id=body.get("id").asString();
            String url=body.get("sourceImage").get("url").asString();
            assertThat(url).doesNotContain("products/", "minio", "storage_key");
            assertThat(storage.data.values()).anySatisfy(bytes -> assertThat(bytes).isEqualTo(original));
            mvc.perform(get("/api/v1/products/"+id).cookie(cookie)).andExpect(status().isOk());
            mvc.perform(get(URI.create(url))).andExpect(status().isOk()).andExpect(content().bytes(original));
            mvc.perform(get(URI.create(url.replace("signature=", "signature=invalid")))).andExpect(status().isForbidden());
            mvc.perform(get(URI.create(url.replaceAll("expires=[0-9]+", "expires=1")))).andExpect(status().isForbidden());
        }
    }
    @Test void ownerIsolationBearerPriorityAndOrigin() throws Exception {
        Cookie cookie=trial();
        String id=upload(cookie);
        var token=bearer("owner@test.example");
        mvc.perform(get("/api/v1/products/"+id).header("Authorization", "Bearer "+token).cookie(cookie))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products/prd_missing").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/products").file(image()).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/products").file(image()).cookie(cookie).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        var own=mvc.perform(multipart("/api/v1/products").file(image()).header("Authorization", "Bearer "+token))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String ownId=json.readTree(own.getContentAsString()).get("id").asString();
        mvc.perform(get("/api/v1/products/"+ownId).header("Authorization", "Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/"+ownId).cookie(cookie)).andExpect(status().isNotFound());
    }
    @Test void validationRejectsSpoofedMimeCorruptionEmptyMissingAndOversize() throws Exception {
        Cookie cookie=trial();
        expectUpload(cookie, new byte[]{1,2,3}, "image/png", 415, "UNSUPPORTED_FORMAT");
        expectUpload(cookie, new byte[0], "image/png", 400, "UPLOAD_VALIDATION_ERROR");
        expectUpload(cookie, Arrays.copyOf(fixture("png"), 20), "image/png", 422, "CORRUPTED_IMAGE");
        expectUpload(cookie, new byte[10485761], "image/png", 413, "FILE_TOO_LARGE");
        mvc.perform(multipart("/api/v1/products").cookie(cookie).header("Origin", "https://ui.test"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/products").file(image()).param("name", " ").cookie(cookie).header("Origin", "https://ui.test"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isZero();
    }
    @Test void realServletRejectsOversizeMultipart() throws Exception {
        String token=bearer("size@test.example");
        String boundary="testBoundary";
        byte[] start=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"x.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] end=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(start),
                HttpRequest.BodyPublishers.ofByteArray(new byte[10485761]), HttpRequest.BodyPublishers.ofByteArray(end));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/products"))
                .header("Authorization", "Bearer "+token).header("Content-Type", "multipart/form-data; boundary="+boundary)
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("FILE_TOO_LARGE");
        assertThat(storage.data).isEmpty();
    }
    @Test void storageFailuresAreCompensatedAndRetriedAfterCrash() throws Exception {
        Cookie cookie=trial();
        storage.failPut=true; storage.failDelete=true;
        mvc.perform(multipart("/api/v1/products").file(image()).cookie(cookie).header("Origin", "https://ui.test"))
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product_uploads", Integer.class)).isEqualTo(1);
        storage.failPut=false; storage.failDelete=false;
        jdbc.update("update product_uploads set created_at = current_timestamp - interval '2 hours'");
        products.cleanupAbandoned();
        assertThat(storage.data).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from product_uploads", Integer.class)).isZero();
    }
    @Test void rateLimitSurvivesRejectedUpload() throws Exception {
        Cookie cookie=trial();
        for (int i=0;i<30;i++) expectUpload(cookie, new byte[]{1}, "image/png", 415, "UNSUPPORTED_FORMAT");
        expectUpload(cookie, fixture("png"), "image/png", 429, "RATE_LIMITED");
    }
    private String bearer(String email) {
        auth.register(email, "correct horse battery staple", null, null);
        return auth.login(email, "correct horse battery staple", new RequestMetadata("Test", "127.0.0.xxx")).accessToken();
    }
    private Cookie trial() throws Exception {
        return mvc.perform(post("/api/v1/trial-session").header("Origin", "https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("__Host-tovarika_trial");
    }
    private String upload(Cookie cookie) throws Exception {
        var response=mvc.perform(multipart("/api/v1/products").file(image()).cookie(cookie).header("Origin", "https://ui.test"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asString();
    }
    private void expectUpload(Cookie cookie, byte[] bytes, String mime, int status, String code) throws Exception {
        mvc.perform(multipart("/api/v1/products").file(new MockMultipartFile("image", "x.png", mime, bytes))
                .cookie(cookie).header("Origin", "https://ui.test"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
    private MockMultipartFile image() throws Exception { return new MockMultipartFile("image", "test.png", "image/png", fixture("png")); }
    private byte[] fixture(String format) throws Exception {
        try (var input=getClass().getResourceAsStream("/images/product."+format)) { return input.readAllBytes(); }
    }
    @TestConfiguration static class StorageConfig {
        @Bean @Primary FakeStorage fakeStorage() { return new FakeStorage(); }
    }
    static class FakeStorage implements ProductStorage {
        final Map<String,byte[]> data=new ConcurrentHashMap<>();
        boolean failPut, failDelete;
        public void put(String key, byte[] bytes, String mediaType) {
            data.put(key, bytes.clone());
            if (failPut) throw new IllegalStateException("storage unavailable");
        }
        public byte[] read(String key) { return data.get(key); }
        public void delete(String key) {
            if (failDelete) throw new IllegalStateException("storage unavailable");
            data.remove(key);
        }
    }
}
