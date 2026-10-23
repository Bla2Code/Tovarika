package com.tovarika.tech.billing.payments;

import static org.assertj.core.api.Assertions.*;
import com.tovarika.tech.billing.application.BillingFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class RobokassaGatewayTest {
    private final String id="pay_"+"a".repeat(32);
    private final RobokassaSettings settings=new RobokassaSettings(com.tovarika.tech.billing.payments.RobokassaMode.TEST,"synthetic-shop","synthetic-test-one","synthetic-test-two",List.of("synthetic-user"));
    private final RobokassaGateway gateway=new RobokassaGateway(settings);
    @Test void calendarMonthUsesMoscowAndClampsMonthEnd() {
        assertThat(PackageLifetime.expiresAt(Instant.parse("2026-10-22T09:10:11Z"))).isEqualTo(Instant.parse("2026-11-22T09:10:11Z"));
        assertThat(PackageLifetime.expiresAt(Instant.parse("2027-01-31T12:00:00Z"))).isEqualTo(Instant.parse("2027-02-28T12:00:00Z"));
        assertThat(PackageLifetime.expiresAt(Instant.parse("2028-01-31T12:00:00Z"))).isEqualTo(Instant.parse("2028-02-29T12:00:00Z"));
        // 31 January at 01:00 in Moscow, despite the UTC date being 30 January.
        assertThat(PackageLifetime.expiresAt(Instant.parse("2027-01-30T22:00:00Z"))).isEqualTo(Instant.parse("2027-02-27T22:00:00Z"));
    }
    @Test void checkoutIsAlwaysTestAndNeverContainsPasswords() {
        String url=gateway.confirmationUrl(id,12,99000,14000);
        assertThat(url).startsWith("https://auth.robokassa.ru/Merchant/Index.aspx?")
                .contains("IsTest=1","OutSum=990.00","InvId=12","Shp_purchaseId="+id)
                .doesNotContain(settings.testPassword1(),settings.testPassword2());
        assertThat(settings.toString()).doesNotContain(settings.testPassword1(),settings.testPassword2());
    }
    @Test void verifiesOriginalSixDecimalAmountAndCaseInsensitiveHex() {
        var parameters=notification("990.000000");
        parameters.set("SignatureValue",parameters.getFirst("SignatureValue").toUpperCase(java.util.Locale.ROOT));
        assertThat(gateway.verify(parameters)).isEqualTo(new RobokassaGateway.Notification(id,12,99000));
        parameters.set("OutSum","2490.000000");
        assertThatThrownBy(()->gateway.verify(parameters)).isInstanceOf(BillingFailure.class);
    }
    @Test void rejectsDuplicateParametersUnknownCustomParametersAndFractionalKopecks() {
        var duplicate=notification("990.00");duplicate.add("InvId","13");
        assertThatThrownBy(()->gateway.verify(duplicate)).isInstanceOf(BillingFailure.class);
        var custom=notification("990.00");custom.set("Shp_other","value");
        assertThatThrownBy(()->gateway.verify(custom)).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(()->gateway.verify(notification("990.000001"))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(()->gateway.verify(notification("9.9e2"))).isInstanceOf(BillingFailure.class);
        var wrong=notification("990.00");wrong.set("SignatureValue","0".repeat(64));
        assertThatThrownBy(()->gateway.verify(wrong)).isInstanceOf(BillingFailure.class);
    }
    @Test void checkoutNeedsExplicitEnablementAndTestUserAllowlist() {
        assertThat(settings.availableFor("synthetic-user")).isTrue();
        assertThat(settings.availableFor("other-user")).isFalse();
        assertThat(settings.availableFor(null)).isFalse();
        assertThat(new RobokassaSettings(com.tovarika.tech.billing.payments.RobokassaMode.DISABLED,null,null,null,null).configured()).isFalse();
    }
    @Test void documentedEnvironmentNamesBindWithoutRuntimeConfiguration() {
        var environment=new org.springframework.mock.env.MockEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("synthetic-systemEnvironment",
                java.util.Map.of("TOVARIKA_PAYMENTS_ROBOKASSA_MODE","test",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_MERCHANTLOGIN","synthetic-shop",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_TESTPASSWORD1","synthetic-one",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_TESTPASSWORD2","synthetic-two",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_TESTUSERIDS","synthetic-user,synthetic-user-two")));
        var bound=org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tovarika.payments.robokassa",org.springframework.boot.context.properties.bind.Bindable.of(RobokassaSettings.class)).get();
        assertThat(bound.mode()).isEqualTo(RobokassaMode.TEST);
        assertThat(bound.merchantLogin()).isEqualTo("synthetic-shop");
        assertThat(bound.testUserIds()).containsExactly("synthetic-user","synthetic-user-two");
        assertThat(bound.configured()).isTrue();
        assertThat(bound.availableFor("synthetic-user")).isTrue();
        assertThat(bound.availableFor("synthetic-user-two")).isTrue();
        assertThat(bound.availableFor("other-user")).isFalse();
    }
    @Test void modeDefaultsToDisabledAndLiveCannotCreatePayments() {
        assertThat(new RobokassaSettings(null,null,null,null,null).mode()).isEqualTo(RobokassaMode.DISABLED);
        for(var mode:List.of(RobokassaMode.DISABLED,RobokassaMode.LIVE)) {
            var configured=new RobokassaSettings(mode,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),settings.testUserIds());
            assertThat(configured.availableFor("synthetic-user")).isFalse();
            assertThatThrownBy(()->new RobokassaGateway(configured).confirmationUrl(id,12,99000,14000)).isInstanceOf(BillingFailure.class);
        }
    }
    @Test void disablingNewCheckoutsDoesNotInvalidateAnExistingTestNotification() {
        var disabled=new RobokassaSettings(RobokassaMode.DISABLED,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),settings.testUserIds());
        assertThat(new RobokassaGateway(disabled).verify(notification("990.00")))
                .isEqualTo(new RobokassaGateway.Notification(id,12,99000));
    }
    @Test void invalidModeIsRejectedByTheBinderRatherThanBecomingTest() {
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("tovarika.payments.robokassa.mode","typo");
        assertThatThrownBy(()->org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tovarika.payments.robokassa",org.springframework.boot.context.properties.bind.Bindable.of(RobokassaSettings.class)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
    private LinkedMultiValueMap<String,String> notification(String sum) {
        var parameters=new LinkedMultiValueMap<String,String>();
        parameters.set("OutSum",sum);parameters.set("InvId","12");parameters.set("Shp_purchaseId",id);
        parameters.set("SignatureValue",RobokassaGateway.sha256(sum+":12:"+settings.testPassword2()+":Shp_purchaseId="+id));
        return parameters;
    }
}
