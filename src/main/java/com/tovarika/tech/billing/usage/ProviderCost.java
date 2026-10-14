package com.tovarika.tech.billing.usage;

import java.math.BigDecimal;
import java.util.*;

/** Conservative estimate for documented response scope only. No tool/root summation. */
public final class ProviderCost {
    private ProviderCost() {}
    public record Rate(String version,String currency,Map<String,BigDecimal> perMillion) {}
    public record Estimate(String state,BigDecimal amount,String version,String currency) {}
    public static Estimate estimate(ResponsesUsage.Component component,Rate rate,boolean stub) {
        if(stub) return new Estimate("stub",null,null,null);
        if(rate==null || component.actualModel()==null || !"response".equals(component.scope()))
            return unknown();
        var c=component.counters();
        Long input=c.get("input_tokens"),cached=c.get("input_cached_tokens"),written=c.get("input_cache_write_tokens"),output=c.get("output_tokens");
        // Missing details are unknown, not assumed zero. No modality pricing is inferred.
        if(input==null || cached==null || written==null || output==null || cached>input || written>input-cached) return unknown();
        var counts=Map.of("input_uncached",input-cached-written,"input_cached",cached,"input_cache_write",written,"output",output);
        BigDecimal amount=BigDecimal.ZERO;
        for(var entry:counts.entrySet()) {
            if(entry.getValue()==0) continue;
            BigDecimal price=rate.perMillion().get(entry.getKey());
            if(price==null || price.signum()<0) return unknown();
            amount=amount.add(price.multiply(BigDecimal.valueOf(entry.getValue())).movePointLeft(6));
        }
        return new Estimate("estimated",amount,rate.version(),rate.currency());
    }
    public static Estimate unknown() { return new Estimate("unknown",null,null,null); }
}
