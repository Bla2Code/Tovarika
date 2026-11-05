package com.tovarika.tech.billing.payments;

import static org.assertj.core.api.Assertions.*;
import com.tovarika.tech.billing.application.BillingFailure;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RobokassaReceiptTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void npdReceiptContainsOneNamedPackageAndTheFullPriceWithoutVat() {
        long[][] packages = {{99000,14000},{249000,40000},{649000,120000},{99001,14050}};
        for (var offer : packages) {
            String receipt = RobokassaReceipt.npd(offer[0],offer[1]);
            var root = json.readTree(receipt);
            assertThat(root.size()).isEqualTo(1);
            assertThat(root.path("items").size()).isEqualTo(1);
            var item = root.path("items").get(0);
            assertThat(item.size()).isEqualTo(4);
            assertThat(item.path("name").asText()).isEqualTo("Доступ к сервису Товарика: пакет "+
                    BigDecimal.valueOf(offer[1],2).stripTrailingZeros().toPlainString()+" Товариков на 1 месяц");
            assertThat(item.path("name").asText().length()).isLessThanOrEqualTo(128);
            assertThat(item.path("quantity").asInt()).isEqualTo(1);
            assertThat(item.path("sum").isNumber()).isTrue();
            assertThat(item.path("sum").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(offer[0],2));
            assertThat(item.path("tax").asText()).isEqualTo("none");
            assertThat(receipt).doesNotContain("\n","sno","payment_method","payment_object");
        }
    }
    @Test void rejectsNonpositivePriceOrCredits() {
        for (long[] invalid : new long[][]{{0,14000},{-1,14000},{99000,0},{99000,-1}}) {
            assertThatThrownBy(()->RobokassaReceipt.npd(invalid[0],invalid[1])).isInstanceOf(BillingFailure.class);
        }
    }
}
