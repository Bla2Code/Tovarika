package com.tovarika.tech.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.auth.application.EmailAuthenticationService;
import com.tovarika.tech.auth.application.RequestMetadata;
import com.tovarika.tech.billing.application.BillingService;
import com.tovarika.tech.billing.domain.Billing.Owner;
import com.tovarika.tech.trial.application.TrialSessionService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false", "tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.ai.openai.mode=stub"
})
class RegistrationOnboardingIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired WebApplicationContext context;
    @Autowired EmailAuthenticationService auth;
    @Autowired BillingService billing;
    @Autowired TrialSessionService trials;
    @Autowired AuthenticationCookieService cookies;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    private static final String PASSWORD = "test registration passphrase";
    private static final String ANSWERS = """
        {"role":"marketplace_seller","frequency":"from_6_to_20","difficulty":"ai_quality"}
        """;

    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    String register() { return auth.register(UUID.randomUUID()+"@example.test",PASSWORD,null,null).user().id(); }

    @Test void newRegistrationGets16TovarikiAndSurveyIsSavedOnlyOnceWithAccountEmail() throws Exception {
        String id=register();
        var owner=new Owner("user",id);
        assertThat(billing.wallet(owner).totalUnits()).isEqualTo(1600);
        mvc.perform(get("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        mvc.perform(put("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))).contentType("application/json").content(ANSWERS))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        var saved=jdbc.queryForMap("select email,role,frequency,difficulty,completed_at from registration_onboarding where user_id=?",id);
        assertThat(saved.get("email")).isEqualTo(jdbc.queryForObject("select email from users where id=?",String.class,id));
        assertThat(saved).containsEntry("role","marketplace_seller").containsEntry("frequency","from_6_to_20").containsEntry("difficulty","ai_quality");
        mvc.perform(put("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))).contentType("application/json")
                .content(ANSWERS.replace("marketplace_seller","exploring_ai")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        assertThat(jdbc.queryForMap("select email,role,frequency,difficulty,completed_at from registration_onboarding where user_id=?",id)).isEqualTo(saved);
        mvc.perform(get("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        String email=(String)saved.get("email");
        auth.login(email,PASSWORD,new RequestMetadata("Test Browser","192.0.2.xxx"));
        assertThat(billing.wallet(owner).totalUnits()).isEqualTo(1600);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants where wallet_id=?",Integer.class,billing.wallet(owner).id())).isEqualTo(1);
    }

    @Test void trialConversionKeepsPhotoAndSingle16TovarikiGrant() {
        var trial=trials.bootstrap(null,"onboarding-"+UUID.randomUUID());
        String product="prd_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into products(id,name,owner_trial_session_id,created_at,updated_at) values(?,'Trial photo',?,now(),now())",product,trial.session().id());
        var user=auth.register(UUID.randomUUID()+"@example.test",PASSWORD,null,trial.rawToken()).user();
        assertThat(jdbc.queryForObject("select owner_user_id from products where id=?",String.class,product)).isEqualTo(user.id());
        assertThat(jdbc.queryForObject("select owner_trial_session_id from products where id=?",String.class,product)).isNull();
        assertThat(billing.wallet(new Owner("user",user.id())).totalUnits()).isEqualTo(1600);
        assertThat(billing.wallet(new Owner("trial",trial.session().id())).id()).isEqualTo(billing.wallet(new Owner("user",user.id())).id());
        auth.login(user.email(),PASSWORD,new RequestMetadata("Test Browser","192.0.2.xxx"));
        assertThat(billing.wallet(new Owner("user",user.id())).totalUnits()).isEqualTo(1600);
    }

    @Test void anonymousGenerationIsRejectedBeforeCreatingCardOrBillingOperation() throws Exception {
        var trial=trials.bootstrap(null,"gate-"+UUID.randomUUID());
        long before=jdbc.queryForObject("select count(*) from billing_operations",Long.class);
        mvc.perform(post("/api/v1/projects/prj_missing/cards").cookie(new Cookie(cookies.trialCookieName(),trial.rawToken()))
                .header("Origin","https://ui.test").header("Idempotency-Key","onboarding-generation")
                .contentType("application/json").content("{\"prompt\":\"A product card\",\"aspectRatio\":\"3:4\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REGISTRATION_REQUIRED"));
        assertThat(jdbc.queryForObject("select count(*) from billing_operations",Long.class)).isEqualTo(before);
    }

    @Test void existingAccountLoginAlsoKeepsTheTrialPhotoAndDoesNotRepeatTheSurveyOrGrant() throws Exception {
        String id=register();
        String email=jdbc.queryForObject("select email from users where id=?",String.class,id);
        mvc.perform(put("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))).contentType("application/json").content(ANSWERS))
            .andExpect(status().isOk());
        var trial=trials.bootstrap(null,"login-"+UUID.randomUUID());
        String product="prd_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into products(id,name,owner_trial_session_id,created_at,updated_at) values(?,'Trial photo',?,now(),now())",product,trial.session().id());
        String body="{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}";
        var cookie=new Cookie(cookies.trialCookieName(),trial.rawToken());
        mvc.perform(post("/api/v1/auth/login").cookie(cookie).header("Origin","https://ui.test")
                .contentType("application/json").content(body.replace(PASSWORD,"wrong test password")))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select owner_user_id from products where id=?",String.class,product)).isNull();
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(post("/api/v1/auth/login").cookie(cookie).header("Origin","https://ui.test")
                    .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id))
                .andExpect(cookie().maxAge(cookies.trialCookieName(),0));
        }
        assertThat(jdbc.queryForObject("select owner_user_id from products where id=?",String.class,product)).isEqualTo(id);
        // Merge the two original funded streams; login itself must never mint another grant.
        var wallet=billing.wallet(new Owner("user",id));
        assertThat(wallet.totalUnits()).isEqualTo(3200);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants g join billing_wallet_links l on l.wallet_id=g.wallet_id where l.canonical_wallet_id=?",Integer.class,wallet.id())).isEqualTo(2);
        mvc.perform(get("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id)))).andExpect(jsonPath("$.completed").value(true));
    }

    @Test void validationIdentityIsolationAndExistingAccounts() throws Exception {
        mvc.perform(get("/api/v1/me/onboarding")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/onboarding").contentType("application/json").content(ANSWERS)).andExpect(status().isUnauthorized());
        String id=register(), other=register();
        for(String body:java.util.List.of("{}",ANSWERS.replace("marketplace_seller","invalid"))) {
            mvc.perform(put("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id))).contentType("application/json").content(body))
                .andExpect(status().is4xxClientError());
        }
        mvc.perform(put("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(other))).contentType("application/json").content(ANSWERS)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(id)))).andExpect(jsonPath("$.completed").value(false));
        String old="usr_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values(?,'old@example.test',true,'ACTIVE',now(),now())",old);
        mvc.perform(get("/api/v1/me/onboarding").with(jwt().jwt(j->j.subject(old)))).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        assertThat(jdbc.queryForObject("select count(*) from registration_onboarding where user_id=?",Integer.class,old)).isZero();
    }
}
