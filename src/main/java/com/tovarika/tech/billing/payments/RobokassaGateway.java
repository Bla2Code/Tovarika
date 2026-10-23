package com.tovarika.tech.billing.payments;

import com.tovarika.tech.billing.application.BillingFailure;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
@EnableConfigurationProperties(RobokassaSettings.class)
public class RobokassaGateway {
    private final RobokassaSettings settings;
    public RobokassaGateway(RobokassaSettings settings) { this.settings = settings; }

    public String confirmationUrl(String purchaseId, long invoiceId, long priceKopecks, long creditUnits) {
        if (!settings.configured()) throw unavailable();
        String sum = BigDecimal.valueOf(priceKopecks, 2).toPlainString();
        var parameters = new LinkedHashMap<String,String>();
        parameters.put("MerchantLogin", settings.merchantLogin());
        parameters.put("OutSum", sum);
        parameters.put("InvId", Long.toString(invoiceId));
        parameters.put("Description", "Товарика " + creditUnits / 100 + " Т на один месяц");
        parameters.put("IsTest", "1");
        parameters.put("Culture", "ru");
        parameters.put("Shp_purchaseId", purchaseId);
        parameters.put("SignatureValue", sha256(settings.merchantLogin()+":"+sum+":"+invoiceId+":"+
                settings.testPassword1()+":Shp_purchaseId="+purchaseId));
        return "https://auth.robokassa.ru/Merchant/Index.aspx?" + parameters.entrySet().stream()
                .map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
    }

    public Notification verify(MultiValueMap<String,String> parameters) {
        // Changing mode blocks new checkouts, but already-created test payments can finish.
        if (!settings.testCredentialsConfigured()) throw unavailable();
        if (parameters.size() > 24 || parameters.values().stream().anyMatch(v -> v.size()!=1)) throw invalid();
        if (parameters.keySet().stream().anyMatch(k -> k.regionMatches(true,0,"Shp_",0,4) && !k.equals("Shp_purchaseId"))) throw invalid();
        String sum = value(parameters,"OutSum"), invoice = value(parameters,"InvId"),
                purchase = value(parameters,"Shp_purchaseId"), signature = value(parameters,"SignatureValue");
        if (!sum.matches("[0-9]{1,10}(\\.[0-9]{1,6})?") || !invoice.matches("[1-9][0-9]{0,15}") ||
                !purchase.matches("pay_[0-9a-f]{32}") || !signature.matches("[0-9a-fA-F]{64}")) throw invalid();
        String expected = sha256(sum+":"+invoice+":"+settings.testPassword2()+":Shp_purchaseId="+purchase);
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(signature))) throw invalid();
        try {
            long kopecks = new BigDecimal(sum).movePointRight(2).longValueExact();
            if (kopecks <= 0) throw invalid();
            return new Notification(purchase, Long.parseLong(invoice), kopecks);
        } catch (ArithmeticException | NumberFormatException failure) { throw invalid(); }
    }
    private static String value(Map<String,? extends java.util.List<String>> parameters,String name) {
        var values = parameters.get(name);
        if (values==null || values.size()!=1 || values.getFirst()==null || values.getFirst().length()>200) throw invalid();
        return values.getFirst();
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static BillingFailure unavailable() { return new BillingFailure(503,"PAYMENT_PROVIDER_UNAVAILABLE","Тестовая оплата пока недоступна"); }
    private static BillingFailure invalid() { return new BillingFailure(400,"VALIDATION_ERROR","Invalid payment notification"); }
    public record Notification(String purchaseId, long invoiceId, long priceKopecks) {}
}
