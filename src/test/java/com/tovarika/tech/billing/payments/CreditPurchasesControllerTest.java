package com.tovarika.tech.billing.payments;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.api.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CreditPurchasesControllerTest {
    private final CreditPurchases purchases=mock(CreditPurchases.class);
    private final String id="pay_"+"b".repeat(32);
    private final RobokassaSettings settings=new RobokassaSettings(com.tovarika.tech.billing.payments.RobokassaMode.TEST,"synthetic-shop","synthetic-one","synthetic-two",List.of("synthetic-user"));
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new CreditPurchasesController(purchases,new RobokassaGateway(settings),
                mock(RequestAuthenticationContext.class),mock(AuthenticationStore.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void signedFormIsDelegatedAndReturnsExactAcknowledgement() throws Exception {
        var notification=new RobokassaGateway.Notification(id,15,99000);
        when(purchases.accept(notification)).thenReturn("OK15");
        mvc.perform(post("/api/v1/billing/robokassa/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("OutSum","990.000000").param("InvId","15").param("Shp_purchaseId",id)
                .param("SignatureValue",RobokassaGateway.sha256("990.000000:15:"+settings.testPassword2()+":Shp_purchaseId="+id)))
                .andExpect(status().isOk()).andExpect(content().string("OK15")).andExpect(header().string("Cache-Control","no-store"));
        verify(purchases).accept(notification);
    }
    @Test void invalidSignatureDoesNotReachFinancialService() throws Exception {
        mvc.perform(post("/api/v1/billing/robokassa/result").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("OutSum","990.00").param("InvId","15").param("Shp_purchaseId",id)
                .param("SignatureValue","0".repeat(64))).andExpect(status().isBadRequest());
        verifyNoInteractions(purchases);
    }
    @Test void browserReturnsOnlyRedirectAndCannotConfirmOrCancelPayments() throws Exception {
        mvc.perform(get("/api/v1/billing/robokassa/success").param("Shp_purchaseId",id))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location","/billing?payment="+id+"&paymentReturn=success"));
        mvc.perform(get("/api/v1/billing/robokassa/fail").param("Shp_purchaseId",id))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location","/billing?payment="+id+"&paymentReturn=fail"));
        mvc.perform(get("/api/v1/billing/robokassa/success").param("Shp_purchaseId","https://invalid.test"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location","/billing"));
        verifyNoInteractions(purchases);
    }
}
