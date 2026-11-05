package com.tovarika.tech.billing.payments;

import com.tovarika.tech.billing.application.BillingFailure;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** NPD merchant: one package, no VAT. Buyer contact is collected on Robokassa's payment form. */
final class RobokassaReceipt {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RobokassaReceipt() {}

    static String npd(long priceKopecks, long creditUnits) {
        if (priceKopecks <= 0 || creditUnits <= 0) throw BillingFailure.validation();
        String credits = BigDecimal.valueOf(creditUnits, 2).stripTrailingZeros().toPlainString();
        var item = new LinkedHashMap<String, Object>();
        item.put("name", "Доступ к сервису Товарика: пакет " + credits + " Товариков на 1 месяц");
        item.put("quantity", 1);
        item.put("sum", BigDecimal.valueOf(priceKopecks, 2));
        item.put("tax", "none");
        // NPD is configured in the merchant account; it is not a supported Receipt.sno value.
        return JSON.writeValueAsString(Map.of("items", List.of(item)));
    }
}
