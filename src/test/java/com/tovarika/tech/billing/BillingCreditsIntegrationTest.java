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
    "tovarika.payments.robokassa.mode=live",
    "tovarika.payments.robokassa.merchant-login=synthetic-shop",
    "tovarika.payments.robokassa.test-password1=synthetic-one",
    "tovarika.payments.robokassa.test-password2=synthetic-two",
    "tovarika.payments.robokassa.live-password1=synthetic-live-one",
    "tovarika.payments.robokassa.live-password2=synthetic-live-two",
    "tovarika.payments.robokassa.test-user-ids=synthetic-payment-user",
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
    @Autowired tools.jackson.databind.ObjectMapper json;
    MockMvc mvc;
    @BeforeEach void setUp() { mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void pricesArePublicAndFinancialRoutesRequireIdentity() throws Exception {
        mvc.perform(get("/api/v1/billing/credits/prices")).andExpect(status().isOk())
            .andExpect(jsonPath("$.trialAllowanceUnits").value(1600)).andExpect(jsonPath("$.prices.length()").value(8));
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
            .andExpect(jsonPath("$.totalUnits").value(1600)).andExpect(jsonPath("$.availableUnits").value(1600))
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.usedUnits").value(300)).andExpect(jsonPath("$.wallet.totalUnits").value(1300));
        var response=mvc.perform(get("/api/v1/billing/credits/history").with(jwt().jwt(j -> j.subject(owner.id()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].kind").value("ai_debit")).andExpect(jsonPath("$.items[0].balanceAfterUnits").value(1200))
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.wallet.totalUnits").value(1600)).andExpect(jsonPath("$.usedUnits").value(0));
    }

    @Test void liveCheckoutIsAvailableToEveryAuthenticatedUser() throws Exception {
        mvc.perform(get("/api/v1/billing/credit-packages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.checkoutAvailable").value(false)).andExpect(jsonPath("$.testMode").value(false));
        mvc.perform(post("/api/v1/billing/credit-purchases")
                .header("Idempotency-Key",Billing.id("checkout_")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"credits_140_month\"}")).andExpect(status().isUnauthorized());
        for(String user:java.util.List.of(Billing.id("usr_"),Billing.id("usr_"))) {
            mvc.perform(get("/api/v1/billing/credit-packages").with(jwt().jwt(j->j.subject(user))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkoutAvailable").value(true))
                .andExpect(jsonPath("$.testMode").value(false));
            mvc.perform(post("/api/v1/billing/credit-purchases").with(jwt().jwt(j->j.subject(user)))
                    .header("Idempotency-Key",Billing.id("checkout_")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"packageId\":\"credits_140_month\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.purchase.testMode").value(false))
                .andExpect(jsonPath("$.confirmationUrl").value(org.hamcrest.Matchers.containsString("IsTest=0")));
        }
    }
    @Test void liveRobokassaResultUpdatesPublicBalanceAndHistoryExactlyOnce() throws Exception {
        String user=Billing.id("usr_");
        var owner=new Owner("user",user);
        transactions.execute(status->billing.grantTrial(owner,owner.id()));
        String checkoutKey=Billing.id("checkout_");
        String checkout=mvc.perform(post("/api/v1/billing/credit-purchases")
                .with(jwt().jwt(j->j.subject(user)))
                .header("Idempotency-Key",checkoutKey)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"credits_140_month\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.purchase.status").value("pending"))
            .andExpect(jsonPath("$.purchase.testMode").value(false))
            .andReturn().getResponse().getContentAsString();
        String id=json.readTree(checkout).path("purchase").path("id").asText();
        long invoice=jdbc.queryForObject("select invoice_id from billing_credit_purchases where id=?",Long.class,id);
        String paymentQuery=java.net.URI.create(json.readTree(checkout).path("confirmationUrl").asText()).getRawQuery();
        var paymentParameters=java.util.Arrays.stream(paymentQuery.split("&")).map(p->p.split("=",2))
                .collect(java.util.stream.Collectors.toMap(p->p[0],p->java.net.URLDecoder.decode(p[1],java.nio.charset.StandardCharsets.UTF_8)));
        String encodedReceipt=paymentParameters.get("Receipt");
        assertThat(paymentQuery).contains("IsTest=0","Receipt=%257B","SignatureValue="+
                paymentSignature("synthetic-shop:990.00:"+invoice+":"+encodedReceipt+":synthetic-live-one:Shp_purchaseId="+id));
        String receipt=java.net.URLDecoder.decode(encodedReceipt,java.nio.charset.StandardCharsets.UTF_8);
        var receiptItem=json.readTree(receipt).path("items").get(0);
        assertThat(receiptItem.path("name").asText()).contains("140 Товариков");
        assertThat(receiptItem.path("quantity").asInt()).isEqualTo(1);
        assertThat(receiptItem.path("sum").decimalValue()).isEqualByComparingTo("990.00");
        assertThat(receiptItem.path("tax").asText()).isEqualTo("none");
        assertThat(jdbc.queryForObject("select receipt_json from billing_credit_purchases where id=?",String.class,id)).isEqualTo(receipt);
        String retryCheckout=mvc.perform(post("/api/v1/billing/credit-purchases")
                .with(jwt().jwt(j->j.subject(user))).header("Idempotency-Key",checkoutKey)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"packageId\":\"credits_140_month\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(retryCheckout).path("confirmationUrl").asText()).isEqualTo(json.readTree(checkout).path("confirmationUrl").asText());

        // Both browser returns are public and only navigate; even Success cannot grant credits.
        for(String outcome:java.util.List.of("fail","success")) {
            mvc.perform(get("/api/v1/billing/robokassa/"+outcome).param("Shp_purchaseId",id))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location","/billing?payment="+id+"&paymentReturn="+outcome));
        }
        mvc.perform(get("/api/v1/billing/credits/wallet").with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.availableUnits").value(1600));
        mvc.perform(post("/api/v1/billing/robokassa/result")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .param("OutSum","990.000000").param("InvId",Long.toString(invoice))
                .param("Shp_purchaseId",id).param("SignatureValue","0".repeat(64)))
            .andExpect(status().isBadRequest());

        // Neither a valid TEST signature nor a signed wrong amount can confirm a LIVE order.
        mvc.perform(post("/api/v1/billing/robokassa/result").contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .param("OutSum","990.000000").param("InvId",Long.toString(invoice)).param("Shp_purchaseId",id).param("IsTest","0")
                .param("SignatureValue",paymentSignature("990.000000:"+invoice+":synthetic-two:Shp_purchaseId="+id)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/billing/robokassa/result").contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .param("OutSum","1.000000").param("InvId",Long.toString(invoice)).param("Shp_purchaseId",id)
                .param("SignatureValue",paymentSignature("1.000000:"+invoice+":synthetic-live-two:Shp_purchaseId="+id)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/billing/credits/wallet").with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.availableUnits").value(1600));
        mvc.perform(get("/api/v1/billing/credit-purchases/"+id).with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));

        // A provider notification has no browser credentials or Origin header.
        String signature=paymentSignature("990.000000:"+invoice+":synthetic-live-two:Shp_purchaseId="+id);
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(post("/api/v1/billing/robokassa/result")
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .param("OutSum","990.000000").param("InvId",Long.toString(invoice))
                    .param("Shp_purchaseId",id).param("SignatureValue",signature))
                .andExpect(status().isOk()).andExpect(content().string("OK"+invoice));
        }
        mvc.perform(get("/api/v1/billing/credit-purchases/"+id).with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.testMode").value(false));
        mvc.perform(get("/api/v1/billing/credits/wallet").with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.availableUnits").value(15600));
        mvc.perform(get("/api/v1/billing/credits/summary").with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.wallet.totalUnits").value(15600))
            .andExpect(jsonPath("$.usedUnits").value(0));
        mvc.perform(get("/api/v1/billing/credits/history").with(jwt().jwt(j->j.subject(user))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].kind").value("package_purchase"))
            .andExpect(jsonPath("$.items[0].amountUnits").value(14000))
            .andExpect(jsonPath("$.items[0].balanceAfterUnits").value(15600));
        mvc.perform(get("/api/v1/billing/credit-purchases/"+id).with(jwt().jwt(j->j.subject("other-user"))))
            .andExpect(status().isNotFound());
    }
    private String paymentSignature(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
