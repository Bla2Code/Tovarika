package com.tovarika.tech.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tovarika.tech.auth.api.AuthenticationCookieService;
import com.tovarika.tech.billing.application.BillingService;
import com.tovarika.tech.billing.domain.Billing;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.tech.trial.application.TrialSessionService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.docker.compose.enabled=false",
    "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false",
    "tovarika.security.cors.allowed-origins=https://ui.test",
    "tovarika.ai.openai.mode=stub"
})
class BillingCreditsIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired WebApplicationContext context;
    @Autowired BillingService billing;
    @Autowired TrialSessionService trials;
    @Autowired AuthenticationCookieService cookies;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setUp() { mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void pricesArePublicAndFinancialRoutesRequireIdentity() throws Exception {
        mvc.perform(get("/api/v1/billing/credits/prices")).andExpect(status().isOk())
            .andExpect(jsonPath("$.trialAllowanceUnits").value(600)).andExpect(jsonPath("$.prices.length()").value(8));
        for(String endpoint:java.util.List.of("wallet","summary","history")) {
            mvc.perform(get("/api/v1/billing/credits/"+endpoint)).andExpect(status().isUnauthorized());
        }
    }
    @Test void trialBearerPrecedenceAndInvalidBearerCannotFallBack() throws Exception {
        var trial=trials.bootstrap(null,"billing-"+Billing.id("rate_"));
        var cookie=new Cookie(cookies.trialCookieName(),trial.rawToken());
        var owner=new Owner("trial",trial.session().id());
        transactions.execute(status -> billing.grantTrial(owner,owner.id()));
        var user=new Owner("user",Billing.id("usr_"));
        var userWallet=billing.wallet(user);
        mvc.perform(get("/api/v1/billing/credits/wallet").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalUnits").value(600)).andExpect(jsonPath("$.availableUnits").value(600))
            .andExpect(jsonPath("$.reservedUnits").doesNotExist());
        mvc.perform(get("/api/v1/billing/credits/wallet").cookie(cookie).with(jwt().jwt(j -> j.subject(user.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userWallet.id())).andExpect(jsonPath("$.totalUnits").value(0));
        mvc.perform(get("/api/v1/billing/credits/wallet").cookie(cookie).header("Authorization","Bearer invalid-test-token"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }
    @Test void historySummaryAndValidationMatchContract() throws Exception {
        var owner=new Owner("user",Billing.id("usr_"));
        transactions.execute(status -> billing.grantTrial(owner,owner.id()));
        var command=new Command("image",Billing.id("key_"),1,Map.of(1,"a".repeat(64)),Code.image_generation,null,"generation",Billing.id("job_"));
        var op=transactions.execute(status -> billing.admit(owner,command).operation());
        transactions.execute(status -> billing.finish(op.snapshot().operationId(),0,Outcome.captured));
        String debit=jdbc.queryForObject("select id from billing_ledger where operation_id=?",String.class,op.snapshot().operationId());
        transactions.execute(status -> billing.compensate(op.walletId(),debit,Billing.id("refund_"),100));
        mvc.perform(get("/api/v1/billing/credits/summary").with(jwt().jwt(j -> j.subject(owner.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.usedUnits").value(300)).andExpect(jsonPath("$.wallet.totalUnits").value(300));
        var response=mvc.perform(get("/api/v1/billing/credits/history").with(jwt().jwt(j -> j.subject(owner.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].kind").value("ai_debit")).andExpect(jsonPath("$.items[0].balanceAfterUnits").value(200))
            .andExpect(jsonPath("$.items[1].kind").value("trial_grant")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("walletId","operationId","jobId","sequence","product","subject","compensation","refund","reservedUnits", "\"nextCursor\":null", "\"operationCode\":null");
        for (String query:java.util.List.of("limit=0","limit=101","sort=bad","fromDate=2026-02-30","fromDate=2026-10-15&toDate=2026-10-14","cursor=invalid")) {
            mvc.perform(get("/api/v1/billing/credits/history?"+query).with(jwt().jwt(j -> j.subject(owner.id()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }
    @Test void applicationAdmissionRequiresCallerTransactionAndReturnsFundsDetails() {
        var owner=new Owner("user",Billing.id("usr_"));
        var command=new Command("image",Billing.id("key_"),1,Map.of(1,"a".repeat(64)),Code.image_generation,null,"generation",Billing.id("job_"));
        assertThatThrownBy(() -> billing.admit(owner,command)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.execute(status -> billing.admit(owner,command)))
            .isInstanceOf(com.tovarika.tech.billing.application.BillingFailure.class)
            .satisfies(e -> { var f=(com.tovarika.tech.billing.application.BillingFailure)e;
                assertThat(f.status()).isEqualTo(402); assertThat(f.details()).containsEntry("requiredUnits",400L).containsEntry("availableUnits",0L); });
    }
    @Test void freeHistoryUsesZeroAmountAndOmitsUnverifiedBalanceInActualJson() throws Exception {
        var owner=new Owner("user",Billing.id("usr_"));
        transactions.execute(status->billing.grantTrial(owner,owner.id()));
        jdbc.update("insert into billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,occurred_at) values(?,'user',?,'product_analysis',clock_timestamp())",Billing.id("http-free_"),owner.id());
        var response=mvc.perform(get("/api/v1/billing/credits/history").with(jwt().jwt(j->j.subject(owner.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].kind").value("free_operation"))
            .andExpect(jsonPath("$.items[0].operationCode").value("product_analysis"))
            .andExpect(jsonPath("$.items[0].amountUnits").value(0))
            .andExpect(jsonPath("$.items[0].balanceAfterUnits").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("\"balanceAfterUnits\":null");
        mvc.perform(get("/api/v1/billing/credits/summary").with(jwt().jwt(j->j.subject(owner.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.wallet.totalUnits").value(600)).andExpect(jsonPath("$.usedUnits").value(0));
    }

}
