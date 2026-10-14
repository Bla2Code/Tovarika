package com.tovarika.tech.billing.usage;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** Explicit, worker-local technical context. Never holds request bodies or provider responses. */
public final class AiCallContext {
    public record Identity(String namespace, String jobId, int attempt, String operationId, String funding) {}
    public final Identity identity;
    private final Clock clock;
    private final List<Call> calls = new ArrayList<>();
    public AiCallContext(Identity identity, Clock clock) { this.identity=identity; this.clock=clock; }
    public List<Call> calls() { return List.copyOf(calls); }
    public void end(Call call) { call.end(clock.instant()); }
    public Call start(String main, String image, String size, String quality, boolean stub) {
        var call=new Call(UUID.randomUUID().toString(),clock.instant(),main,image,size,quality,stub);
        calls.add(call); return call;
    }
    public <T> T fallback(String main, String image, String size, boolean stub, Supplier<T> action) {
        var call=start(main,image,size,null,stub);
        try { T result=action.get(); call.success(); return result; }
        catch(RuntimeException failure) { call.failure(failure); throw failure; }
        finally { call.end(clock.instant()); }
    }
    public static final class Call {
        public final String id;
        public final Instant startedAt;
        public final String requestedMainModel, requestedImageModel, requestedSize, requestedQuality;
        public final boolean stub;
        public Instant finishedAt;
        public String outcome="unknown";
        public Integer httpStatus;
        public ResponsesUsage.Snapshot usage=ResponsesUsage.empty();
        private Call(String id,Instant started,String main,String image,String size,String quality,boolean stub) {
            this.id=id;this.startedAt=started;this.requestedMainModel=ResponsesUsage.identifier(main);
            this.requestedImageModel=ResponsesUsage.identifier(image);this.requestedSize=ResponsesUsage.size(size);
            this.requestedQuality=ResponsesUsage.quality(quality);this.stub=stub;
        }
        public void response(JsonNode response) {
            // Accounting must never invalidate a successful provider result.
            try { usage=ResponsesUsage.parse(response); } catch(RuntimeException ignored) { usage=ResponsesUsage.empty(); }
        }
        public void success() { outcome=stub?"stub":"successful"; }
        public void failure(Throwable failure) {
            outcome="error";
            if(failure instanceof org.springframework.web.client.RestClientResponseException http)
                httpStatus=http.getStatusCode().value();
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException
                        || cause instanceof java.util.concurrent.TimeoutException) { outcome="timeout"; break; }
            }
        }
        public void end(Instant now) { finishedAt=now; }
    }
}
