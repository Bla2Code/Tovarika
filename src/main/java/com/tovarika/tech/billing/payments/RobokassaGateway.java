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

    public String confirmationUrl(String purchaseId, long invoiceId, long priceKopecks, long creditUnits, boolean testMode,
            String receiptJson) {
        if (!settings.configured() || !settings.credentialsConfigured(testMode)) throw unavailable();
        String sum = BigDecimal.valueOf(priceKopecks, 2).toPlainString();
        var parameters = new LinkedHashMap<String,String>();
        parameters.put("MerchantLogin", settings.merchantLogin());
        parameters.put("OutSum", sum);
        parameters.put("InvId", Long.toString(invoiceId));
        parameters.put("Description", "Товарика " + creditUnits / 100 + " Т на один месяц");
        parameters.put("IsTest", testMode ? "1" : "0");
        parameters.put("Culture", "ru");
        parameters.put("Shp_purchaseId", purchaseId);
        // Match Robokassa's form flow: encode Receipt before signing, then encode the query itself.
        // Space must be %20 inside Receipt (encodeURIComponent), rather than the form encoding '+'.
        String receipt = receiptJson == null ? null : encodeReceipt(receiptJson);
        if (receipt != null) parameters.put("Receipt", receipt);
        parameters.put("SignatureValue", sha256(settings.merchantLogin()+":"+sum+":"+invoiceId+":"+
                (receipt == null ? "" : receipt+":")+
                settings.password1(testMode)+":Shp_purchaseId="+purchaseId));
        return "https://auth.robokassa.ru/Merchant/Index.aspx?" + parameters.entrySet().stream()
                .map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
    }

    /** Validate input before looking up an order; parsing alone does not authenticate it. */
    public ResultParameters parse(MultiValueMap<String,String> parameters) {
        if (parameters.size() > 24 || parameters.values().stream().anyMatch(v -> v.size()!=1)) throw invalid();
        if (parameters.keySet().stream().anyMatch(k -> k.regionMatches(true,0,"Shp_",0,4) && !k.equals("Shp_purchaseId"))) throw invalid();
        String sum = value(parameters,"OutSum"), invoice = value(parameters,"InvId"),
                purchase = value(parameters,"Shp_purchaseId"), signature = value(parameters,"SignatureValue");
        if (!sum.matches("[0-9]{1,10}(\\.[0-9]{1,6})?") || !invoice.matches("[1-9][0-9]{0,15}") ||
                !purchase.matches("pay_[0-9a-f]{32}") || !signature.matches("[0-9a-fA-F]{64}")) throw invalid();
        try {
            long kopecks = new BigDecimal(sum).movePointRight(2).longValueExact();
            if (kopecks <= 0) throw invalid();
            return new ResultParameters(purchase, Long.parseLong(invoice), kopecks, sum, signature);
        } catch (ArithmeticException | NumberFormatException failure) { throw invalid(); }
    }
    public void verify(ResultParameters notification, boolean testMode) {
        // Select credentials by the stored order, independently of checkout enablement or incoming IsTest.
        if (!settings.credentialsConfigured(testMode)) throw unavailable();
        String expected = sha256(notification.outSum()+":"+notification.invoiceId()+":"+
                settings.password2(testMode)+":Shp_purchaseId="+notification.purchaseId());
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(notification.signature()))) throw invalid();
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
    private static String encodeReceipt(String value) {
        return encode(value).replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }
    public static BillingFailure unavailable() { return new BillingFailure(503,"PAYMENT_PROVIDER_UNAVAILABLE","Оплата пока недоступна"); }
    private static BillingFailure invalid() { return new BillingFailure(400,"VALIDATION_ERROR","Invalid payment notification"); }
    public record ResultParameters(String purchaseId, long invoiceId, long priceKopecks, String outSum, String signature) {}
}
