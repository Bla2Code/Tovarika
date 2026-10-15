package com.tovarika.tech.billing.usage;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Allowlist, schema v1. Aggregates and subsets are diagnostic counters, never additive charges. */
public final class ResponsesUsage {
    private ResponsesUsage() {}
    public record Component(String scope,String id,String actualModel,String size,String quality,
                            String status,Map<String,Long> counters,String classification) {}
    public record Snapshot(String responseId,String actualMainModel,Long createdAt,Long completedAt,
                           List<Component> components) {}
    public static Snapshot empty() { return new Snapshot(null,null,null,null,List.of()); }
    public static String identifier(String value) {
        return value!=null && value.matches("[a-zA-Z0-9_.:-]{1,160}")?value:null;
    }
    public static String size(String value) { return value!=null && value.matches("[0-9]{1,5}x[0-9]{1,5}")?value:null; }
    public static String quality(String value) { return Set.of("auto","low","medium","high").contains(value==null?"":value)?value:null; }
    private static String text(JsonNode node,String field) {
        return node.path(field).isString()?identifier(node.path(field).asText()):null;
    }
    private static String status(JsonNode node) {
        String value=text(node,"status");
        return Set.of("queued","in_progress","completed","failed","incomplete","cancelled").contains(value==null?"":value)?value:null;
    }
    private static Long number(JsonNode node,String field) {
        JsonNode value=node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>=0?value.asLong():null;
    }
    private static void add(Map<String,Long> values,String key,JsonNode node,String field) {
        Long value=number(node,field); if(value!=null) values.put(key,value);
    }
    private static Map<String,Long> counters(JsonNode usage) {
        var values=new LinkedHashMap<String,Long>();
        for(String field:List.of("input_tokens","output_tokens","total_tokens")) add(values,field,usage,field);
        for(String field:List.of("cached_tokens","cache_write_tokens","text_tokens","image_tokens"))
            add(values,"input_"+field,usage.path("input_tokens_details"),field);
        for(String field:List.of("reasoning_tokens","text_tokens","image_tokens"))
            add(values,"output_"+field,usage.path("output_tokens_details"),field);
        return Map.copyOf(values);
    }
    public static Snapshot parse(JsonNode response) {
        if(response==null || !response.isObject()) return empty();
        var components=new ArrayList<Component>();
        String actual=text(response,"model");
        components.add(new Component("response",text(response,"id"),actual,null,null,status(response),
                counters(response.path("usage")),"documented_response_v1"));
        // Tool-level token breakdown is not established by the Responses docs. Preserve whitelisted
        // extension counters separately, with no monetary estimate and no fallback to requested model.
        var seen=new HashSet<String>();
        JsonNode output=response.path("output");
        if(output.isArray()) for(JsonNode item:output) {
            if(!"image_generation_call".equals(item.path("type").asText())) continue;
            String id=text(item,"id");
            if(id!=null && !seen.add(id)) continue;
            components.add(new Component("image_tool",id,text(item,"model"),size(item.path("size").asText()),
                    quality(item.path("quality").asText()),status(item),counters(item.path("usage")),"unverified_tool_usage"));
        }
        return new Snapshot(text(response,"id"),actual,number(response,"created_at"),number(response,"completed_at"),List.copyOf(components));
    }
}
