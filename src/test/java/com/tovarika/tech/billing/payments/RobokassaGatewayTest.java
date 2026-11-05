package com.tovarika.tech.billing.payments;

import static org.assertj.core.api.Assertions.*;
import com.tovarika.tech.billing.application.BillingFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class RobokassaGatewayTest {
    private final String id="pay_"+"a".repeat(32);
    private final RobokassaSettings settings=new RobokassaSettings(com.tovarika.tech.billing.payments.RobokassaMode.TEST,"synthetic-shop","synthetic-test-one","synthetic-test-two",List.of("synthetic-user"),"synthetic-live-one","synthetic-live-two");
    private final RobokassaGateway gateway=new RobokassaGateway(settings);
    @Test void calendarMonthUsesMoscowAndClampsMonthEnd() {
        assertThat(PackageLifetime.expiresAt(Instant.parse("2026-10-22T09:10:11Z"))).isEqualTo(Instant.parse("2026-11-22T09:10:11Z"));
        assertThat(PackageLifetime.expiresAt(Instant.parse("2027-01-31T12:00:00Z"))).isEqualTo(Instant.parse("2027-02-28T12:00:00Z"));
        assertThat(PackageLifetime.expiresAt(Instant.parse("2028-01-31T12:00:00Z"))).isEqualTo(Instant.parse("2028-02-29T12:00:00Z"));
        // 31 January at 01:00 in Moscow, despite the UTC date being 30 January.
        assertThat(PackageLifetime.expiresAt(Instant.parse("2027-01-30T22:00:00Z"))).isEqualTo(Instant.parse("2027-02-27T22:00:00Z"));
    }
    @Test void testCheckoutUsesTestParametersAndNeverContainsPasswords() {
        String url=gateway.confirmationUrl(id,12,99000,14000,true,null);
        assertThat(url).startsWith("https://auth.robokassa.ru/Merchant/Index.aspx?")
                .contains("IsTest=1","OutSum=990.00","InvId=12","Shp_purchaseId="+id)
                .doesNotContain(settings.testPassword1(),settings.testPassword2());
        assertThat(settings.toString()).doesNotContain(settings.testPassword1(),settings.testPassword2());
    }
    @Test void verifiesOriginalSixDecimalAmountAndCaseInsensitiveHex() {
        var parameters=notification("990.000000");
        parameters.set("SignatureValue",parameters.getFirst("SignatureValue").toUpperCase(java.util.Locale.ROOT));
        assertThat(verified(parameters)).isEqualTo(new RobokassaGateway.ResultParameters(id,12,99000,"990.000000",parameters.getFirst("SignatureValue")));
        parameters.set("OutSum","2490.000000");
        assertThatThrownBy(()->verified(parameters)).isInstanceOf(BillingFailure.class);
    }
    @Test void rejectsDuplicateParametersUnknownCustomParametersAndFractionalKopecks() {
        var duplicate=notification("990.00");duplicate.add("InvId","13");
        assertThatThrownBy(()->verified(duplicate)).isInstanceOf(BillingFailure.class);
        var custom=notification("990.00");custom.set("Shp_other","value");
        assertThatThrownBy(()->verified(custom)).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(()->verified(notification("990.000001"))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(()->verified(notification("9.9e2"))).isInstanceOf(BillingFailure.class);
        var wrong=notification("990.00");wrong.set("SignatureValue","0".repeat(64));
        assertThatThrownBy(()->verified(wrong)).isInstanceOf(BillingFailure.class);
    }
    @Test void checkoutNeedsExplicitEnablementAndTestUserAllowlist() {
        assertThat(settings.availableFor("synthetic-user")).isTrue();
        assertThat(settings.availableFor("other-user")).isFalse();
        assertThat(settings.availableFor(null)).isFalse();
        assertThat(new RobokassaSettings(com.tovarika.tech.billing.payments.RobokassaMode.DISABLED,null,null,null,null,null,null).configured()).isFalse();
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
    @Test void disabledOrMissingLiveCredentialsCannotCreatePayments() {
        assertThat(new RobokassaSettings(null,null,null,null,null,null,null).mode()).isEqualTo(RobokassaMode.DISABLED);
        for(var mode:List.of(RobokassaMode.DISABLED,RobokassaMode.LIVE)) {
            var configured=new RobokassaSettings(mode,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),settings.testUserIds(),null,null);
            assertThat(configured.availableFor("synthetic-user")).isFalse();
            assertThatThrownBy(()->new RobokassaGateway(configured).confirmationUrl(id,12,99000,14000,false,null)).isInstanceOf(BillingFailure.class);
        }
    }
    @Test void workingCredentialsBindFromDocumentedEnvironmentNamesWithoutTestCredentials() {
        var environment=new org.springframework.mock.env.MockEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("synthetic-live-systemEnvironment",
                java.util.Map.of("TOVARIKA_PAYMENTS_ROBOKASSA_MODE","live",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_MERCHANTLOGIN","synthetic-shop",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_LIVEPASSWORD1","synthetic-live-one",
                    "TOVARIKA_PAYMENTS_ROBOKASSA_LIVEPASSWORD2","synthetic-live-two")));
        var bound=org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tovarika.payments.robokassa",org.springframework.boot.context.properties.bind.Bindable.of(RobokassaSettings.class)).get();
        assertThat(bound.mode()).isEqualTo(RobokassaMode.LIVE);
        assertThat(bound.testCredentialsConfigured()).isFalse();
        assertThat(bound.livePassword1()).isEqualTo("synthetic-live-one");
        assertThat(bound.livePassword2()).isEqualTo("synthetic-live-two");
        assertThat(bound.availableFor("ordinary-user")).isTrue();
    }
    @Test void notificationsUseStoredModeEvenAfterDisablingOrSwitchingCheckoutMode() {
        for(var mode:RobokassaMode.values()) {
            var configured=new RobokassaSettings(mode,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),settings.testUserIds(),settings.livePassword1(),settings.livePassword2());
            var configuredGateway=new RobokassaGateway(configured);
            assertThatCode(()->configuredGateway.verify(configuredGateway.parse(notification("990.00")),true)).doesNotThrowAnyException();
            var live=notification("990.000000");
            live.set("SignatureValue",RobokassaGateway.sha256("990.000000:12:"+settings.livePassword2()+":Shp_purchaseId="+id));
            assertThatCode(()->configuredGateway.verify(configuredGateway.parse(live),false)).doesNotThrowAnyException();
            // IsTest is not authentication; a test signature cannot confirm a LIVE order, or vice versa.
            var test=notification("990.00");test.set("IsTest","0");
            assertThatThrownBy(()->configuredGateway.verify(configuredGateway.parse(test),false)).isInstanceOf(BillingFailure.class);
            live.set("IsTest","1");
            assertThatThrownBy(()->configuredGateway.verify(configuredGateway.parse(live),true)).isInstanceOf(BillingFailure.class);
        }
    }
    @Test void liveCheckoutIsAvailableToAllUsersAndUsesWorkingPassword() {
        var live=new RobokassaSettings(RobokassaMode.LIVE,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),List.of(),settings.livePassword1(),settings.livePassword2());
        assertThat(live.availableFor("ordinary-user")).isTrue();
        assertThat(live.availableFor("another-user")).isTrue();
        assertThat(live.availableFor(null)).isFalse();
        assertThat(live.availableFor("")).isFalse();
        var liveGateway=new RobokassaGateway(live);
        String url=liveGateway.confirmationUrl(id,12,99000,14000,false,null);
        assertThat(url).contains("IsTest=0","OutSum=990.00","SignatureValue="+RobokassaGateway.sha256("synthetic-shop:990.00:12:"+live.livePassword1()+":Shp_purchaseId="+id))
                .doesNotContain(live.livePassword1(),live.livePassword2(),live.testPassword1(),live.testPassword2());
        assertThat(live.toString()).doesNotContain(live.livePassword1(),live.livePassword2(),live.testPassword1(),live.testPassword2());
        // Reusing a pending TEST order after switching modes keeps its original payment parameters.
        assertThat(liveGateway.confirmationUrl(id,12,99000,14000,true,null)).contains("IsTest=1", "SignatureValue="+RobokassaGateway.sha256("synthetic-shop:990.00:12:"+live.testPassword1()+":Shp_purchaseId="+id));
    }
    @Test void liveNeverUsesTestCredentialsAsFallback() {
        var same=new RobokassaSettings(RobokassaMode.LIVE,settings.merchantLogin(),settings.testPassword1(),settings.testPassword2(),List.of(),settings.testPassword1(),settings.testPassword2());
        assertThat(same.configured()).isFalse();
        assertThatThrownBy(()->new RobokassaGateway(same).verify(new RobokassaGateway(same).parse(notification("990.00")),false)).isInstanceOf(BillingFailure.class);
    }
    @Test void invalidModeIsRejectedByTheBinderRatherThanBecomingTest() {
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("tovarika.payments.robokassa.mode","typo");
        assertThatThrownBy(()->org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("tovarika.payments.robokassa",org.springframework.boot.context.properties.bind.Bindable.of(RobokassaSettings.class)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
    @Test void receiptUsesDocumentedDoubleEncodingAndKnownSha256SignaturesInBothModes() {
        String receipt="{\"items\":[{\"name\":\"Доступ к сервису Товарика: пакет 140 Товариков на 1 месяц\",\"quantity\":1,\"sum\":990.00,\"tax\":\"none\"}]}";
        assertThat(RobokassaReceipt.npd(99000,14000)).isEqualTo(receipt);
        for (boolean testMode : new boolean[]{true,false}) {
            String url=gateway.confirmationUrl(id,12,99000,14000,testMode,receipt);
            var parameters=query(url);
            assertThat(url).contains("Receipt=%257B").hasSizeLessThan(2000);
            String encodedReceipt=parameters.get("Receipt");
            assertThat(encodedReceipt).startsWith("%7B").contains("%20").doesNotContain("+");
            assertThat(java.net.URLDecoder.decode(encodedReceipt,java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(receipt);
            assertThat(parameters.get("IsTest")).isEqualTo(testMode ? "1" : "0");
            // Independent fixed vectors, using UTF-8 encodeURIComponent(Receipt) before Password #1.
            assertThat(parameters.get("SignatureValue")).isEqualTo(testMode ?
                    "5beb8a60c326c58cb1368dc6526dbfe57c0da6a880c2464a7669df172bc531c0" :
                    "cabbb5e64246aba1d4fd9f44e81febb041aeca95ab98d39e62a01849edff1abd");
        }
    }
    @Test void receiptRoundTripsSpecialCharactersAndLegacyOrdersKeepTheirOriginalSignature() {
        String receipt="{\"items\":[{\"name\":\"A & B + 50% / !'()*~\",\"quantity\":1,\"sum\":990.00,\"tax\":\"none\"}]}";
        var parameters=query(gateway.confirmationUrl(id,12,99000,14000,false,receipt));
        assertThat(parameters.get("Receipt")).contains("%20","%26","%2B","%25","%2F","!'()*~");
        assertThat(java.net.URLDecoder.decode(parameters.get("Receipt"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(receipt);
        var legacy=query(gateway.confirmationUrl(id,12,99000,14000,false,null));
        assertThat(legacy).doesNotContainKey("Receipt");
        assertThat(legacy.get("SignatureValue")).isEqualTo(RobokassaGateway.sha256("synthetic-shop:990.00:12:"+settings.livePassword1()+":Shp_purchaseId="+id));
    }
    private java.util.Map<String,String> query(String url) {
        return java.util.Arrays.stream(java.net.URI.create(url).getRawQuery().split("&"))
                .map(p->p.split("=",2)).collect(java.util.stream.Collectors.toMap(p->p[0],
                        p->java.net.URLDecoder.decode(p[1],java.nio.charset.StandardCharsets.UTF_8)));
    }
    private RobokassaGateway.ResultParameters verified(org.springframework.util.MultiValueMap<String,String> parameters) {
        var parsed=gateway.parse(parameters);
        gateway.verify(parsed,true);
        return parsed;
    }
    private LinkedMultiValueMap<String,String> notification(String sum) {
        var parameters=new LinkedMultiValueMap<String,String>();
        parameters.set("OutSum",sum);parameters.set("InvId","12");parameters.set("Shp_purchaseId",id);
        parameters.set("SignatureValue",RobokassaGateway.sha256(sum+":12:"+settings.testPassword2()+":Shp_purchaseId="+id));
        return parameters;
    }
}
