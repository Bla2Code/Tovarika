package com.tovarika.tech.billing.recovery;

import com.tovarika.tech.products.application.ProductStorage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Heartbeats never acquire financial locks or hold a transaction across external calls. */
@Service
public class AiJobRecovery {
    public static final Duration LEASE = Duration.ofMinutes(10);
    public static final Duration MAX_AGE = Duration.ofHours(24);
    public static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        var thread = new Thread(r, "ai-job-heartbeat"); thread.setDaemon(true); return thread;
    });
    public AiJobRecovery(JdbcTemplate jdbc, Clock clock, MeterRegistry metrics) {
        this.jdbc=jdbc; this.clock=clock; this.metrics=metrics;
    }
    public Lease start(String namespace, String job, int attempt) {
        String table=table(namespace);
        var deadline=clock.instant().plus(Duration.ofMinutes(20));
        var task=executor.scheduleAtFixedRate(() -> {
            if(clock.instant().isBefore(deadline)) {
                try { renew(table,job,attempt); }
                catch(RuntimeException failure) { metrics.counter("tovarika.billing.heartbeat.failures").increment(); }
            }
        },30,30,TimeUnit.SECONDS);
        return () -> task.cancel(false);
    }
    public boolean renew(String namespace,String job,int attempt) {
        String table=table(namespace);
        return jdbc.update("update "+table+" set lease_until=? where id=? and status='processing' and attempt=?",
                Timestamp.from(clock.instant().plus(LEASE)),job,attempt)==1;
    }
    private String table(String namespace) {
        if(!namespace.equals("project_jobs") && !namespace.equals("analysis_jobs")) throw new IllegalArgumentException("Unknown job namespace");
        return namespace;
    }
    public void check(int attempt,Instant createdAt) {
        if(attempt>MAX_ATTEMPTS || !createdAt.plus(MAX_AGE).isAfter(clock.instant()))
            throw new IllegalStateException("Recovery limit reached");
    }
    /** After an ambiguous commit, keep storage whenever its DB ownership is unknown. */
    public void deleteUnreferenced(ProductStorage storage,String key) {
        try {
            if(jdbc.queryForObject("select count(*) from assets where storage_key=?",Long.class,key)==0) storage.delete(key);
        } catch(RuntimeException failure) { metrics.counter("tovarika.billing.artifact.cleanup.deferred").increment(); }
    }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
    public interface Lease extends AutoCloseable { @Override void close(); }
}
