package com.tovarika.tech.billing.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tovarika.api.publicapi.model.BillingOperationCodeDto;
import com.tovarika.api.publicapi.model.BillingOperationSnapshotDto;
import com.tovarika.api.publicapi.model.AsyncOperationDto;
import com.tovarika.api.publicapi.model.JobDto;
import com.tovarika.api.publicapi.model.CreditHistoryEntryDto;
import com.tovarika.api.publicapi.model.CreditHistoryPageDto;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional history fields must be absent; generated DTOs must remain untouched. */
@Configuration
public class BillingJsonConfiguration {
    @Bean JsonMapperBuilderCustomizer billingHistoryJson() {
        return builder -> builder.addMixIn(CreditHistoryEntryDto.class, EntryMixin.class)
                .addMixIn(CreditHistoryPageDto.class, PageMixin.class)
                .addMixIn(AsyncOperationDto.class, JobBillingMixin.class)
                .addMixIn(JobDto.class, JobBillingMixin.class);
    }
    abstract static class JobBillingMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract BillingOperationSnapshotDto getBilling();
    }
    abstract static class EntryMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract BillingOperationCodeDto getOperationCode();
        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract Long getBalanceAfterUnits();
    }
    abstract static class PageMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getNextCursor();
    }
}
