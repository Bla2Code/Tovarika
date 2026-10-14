package com.tovarika.tech.billing.usage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResponsesUsageTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
    static AiCallContext context() { return new AiCallContext(new AiCallContext.Identity("analysis_jobs","job_test",1,null,"free_operation"),CLOCK); }
    static String fixture() throws Exception {
        try(var input=ResponsesUsageTest.class.getResourceAsStream("/billing/response-usage.json")) {
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void preservesSeparateScopesActualModelsAndAllowlistOnly() throws Exception {
        var usage=ResponsesUsage.parse(JSON.readTree(fixture()));
        assertThat(usage.responseId()).isEqualTo("resp_fixture");
        assertThat(usage.actualMainModel()).isEqualTo("main-actual-2026-10-01");
        assertThat(usage.createdAt()).isEqualTo(1791936000L);
        assertThat(usage.components()).hasSize(2);
        var root=usage.components().getFirst();var image=usage.components().get(1);
        assertThat(root.counters()).containsEntry("input_tokens",100L).containsEntry("input_cached_tokens",20L)
                .containsEntry("output_reasoning_tokens",30L).containsEntry("total_tokens",150L);
        assertThat(image.actualModel()).isNull();
        assertThat(image.counters()).containsEntry("input_text_tokens",10L).containsEntry("input_image_tokens",30L);
        assertThat(image.classification()).isEqualTo("unverified_tool_usage");
        assertThat(JSON.writeValueAsString(usage)).doesNotContain("PRIVATE_","result","instructions","api_key","image_url","revised_prompt");
    }
    @Test void missingInvalidAndOverflowCountersStayUnknown() {
        var usage=ResponsesUsage.parse(JSON.readTree("""
                {"model":"test","usage":{"input_tokens":-1,"output_tokens":1.5,"total_tokens":999999999999999999999999}}
                """));
        assertThat(usage.components().getFirst().counters()).isEmpty();
        assertThat(ProviderCost.estimate(usage.components().getFirst(),rate(),false).state()).isEqualTo("unknown");
        assertThat(ResponsesUsage.parse(null).components()).isEmpty();
    }
    static ProviderCost.Rate rate() { return new ProviderCost.Rate("fake-v1","USD",Map.of(
            "input_uncached",new BigDecimal("2"),"input_cached",new BigDecimal("1"),
            "input_cache_write",new BigDecimal("3"),"output",new BigDecimal("4"))); }
    @Test void doesNotChargeAggregatesReasoningOrImageToolTwice() throws Exception {
        var usage=ResponsesUsage.parse(JSON.readTree(fixture()));
        var estimate=ProviderCost.estimate(usage.components().getFirst(),rate(),false);
        // (70*2 + 20*1 + 10*3 + 50*4)/1e6, no total_tokens or extra reasoning charge.
        assertThat(estimate.amount()).isEqualByComparingTo("0.000390");
        assertThat(estimate.version()).isEqualTo("fake-v1");
        assertThat(ProviderCost.estimate(usage.components().get(1),rate(),false).amount()).isNull();
        assertThat(ProviderCost.estimate(usage.components().getFirst(),null,false).amount()).isNull();
        assertThat(ProviderCost.estimate(usage.components().getFirst(),rate(),true).state()).isEqualTo("stub");
    }
    @Test void invalidCachePartitionsAndMissingRateDoNotBecomeZero() {
        var root=ResponsesUsage.parse(JSON.readTree("""
                {"model":"actual","usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":8,"cache_write_tokens":8},"output_tokens":5}}
                """ )).components().getFirst();
        assertThat(ProviderCost.estimate(root,rate(),false).amount()).isNull();
        var incomplete=new ResponsesUsage.Component("response","r","actual",null,null,"completed",
                Map.of("input_tokens",10L,"input_cached_tokens",0L,"input_cache_write_tokens",0L,"output_tokens",5L),"documented_response_v1");
        assertThat(ProviderCost.estimate(incomplete,new ProviderCost.Rate("fake","USD",Map.of()),false).amount()).isNull();
    }
    @Test void deduplicatesImageItemIdsWithoutSummingNestedUsage() {
        var usage=ResponsesUsage.parse(JSON.readTree("""
                {"output":[{"id":"ig_same","type":"image_generation_call","usage":{"input_tokens":5}},
                 {"id":"ig_same","type":"image_generation_call","usage":{"input_tokens":5}}]}
                """));
        assertThat(usage.components()).hasSize(2);
    }
    @Test void boundedRetryOnlySavesMetadataAndEmitsLossMetric() {
        var store=mock(AiUsageStore.class); var metrics=new SimpleMeterRegistry();
        var service=new AiUsageService(store,CLOCK,metrics);var context=context();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(context.fallback("requested",null,null,false,()->calls.incrementAndGet())).isEqualTo(1);
        doThrow(new IllegalStateException("PRIVATE_KEY")).when(store).save(any(),any(),any());
        service.finish(context,"completed");
        assertThat(calls.get()).isEqualTo(1);
        verify(store,times(3)).save(any(),any(),eq("completed"));
        assertThat(metrics.get("tovarika.billing.ai.usage").tag("event","metadata_lost").counter().count()).isEqualTo(1);
    }
    @Test void transientFailureRecoversWithoutProviderRetryAndIdentityFailureKeepsFreeAnalysis() {
        var store=mock(AiUsageStore.class); var metrics=new SimpleMeterRegistry();
        var service=new AiUsageService(store,CLOCK,metrics);
        when(store.identity(any(),any(),anyInt())).thenThrow(new IllegalStateException("PRIVATE"));
        var context=service.context("analysis_jobs","job_test",1);
        assertThat(context.identity.funding()).isEqualTo("free_operation");
        context.fallback("requested",null,null,true,()->1);
        doThrow(new IllegalStateException()).doNothing().when(store).save(any(),any(),any());
        service.finish(context,"stale");
        verify(store,times(2)).save(any(),any(),eq("stale"));
        assertThat(context.calls().getFirst().outcome).isEqualTo("stub");
    }
    @Test void timeoutAndErrorCategoriesDoNotRetainMessages() {
        var context=context();
        assertThatThrownBy(()->context.fallback("main",null,null,false,()->{
            throw new IllegalStateException("PRIVATE",new java.net.SocketTimeoutException("PRIVATE"));
        })).isInstanceOf(IllegalStateException.class);
        assertThat(context.calls().getFirst().outcome).isEqualTo("timeout");
        assertThat(context.calls().getFirst().finishedAt).isNotNull();
    }
}
