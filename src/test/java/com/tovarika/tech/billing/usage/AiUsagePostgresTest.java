package com.tovarika.tech.billing.usage;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class AiUsagePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    static JdbcTemplate jdbc;static JdbcAiUsageStore store;static TransactionTemplate tx;
    static final Clock CLOCK=ResponsesUsageTest.CLOCK;
    @BeforeAll static void migrate() throws Exception {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        jdbc=new JdbcTemplate(ds);store=new JdbcAiUsageStore(jdbc,new ObjectMapper());
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            var db=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try(var lb=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),db)) {
                lb.update(new Contexts(),new LabelExpression());lb.update(new Contexts(),new LabelExpression());
            }
        }
    }
    @BeforeEach void clean() { jdbc.execute("truncate billing_ai_attempts cascade"); }
    AiCallContext.Call call(AiCallContext context) throws Exception {
        var call=context.start("requested-main","requested-image","1024x1536","auto",false);
        call.response(new ObjectMapper().readTree(ResponsesUsageTest.fixture()));call.success();call.end(CLOCK.instant());return call;
    }
    void save(AiCallContext c,AiCallContext.Call call,String outcome) { tx.executeWithoutResult(t->store.save(c.identity,call,outcome)); }
    @Test void idempotentMetadataAndNoWorkspaceForeignKeysOrSecrets() throws Exception {
        var c=ResponsesUsageTest.context();var call=call(c);save(c,call,"completed");save(c,call,"completed");
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_usage",Integer.class)).isEqualTo(2);
        String content=jdbc.queryForObject("select row_to_json(a)::text from billing_ai_attempts a",String.class)
                +jdbc.queryForList("select row_to_json(u)::text from billing_ai_usage u",String.class);
        assertThat(content).doesNotContain("PRIVATE_","api_key","revised_prompt");
        assertThat(jdbc.queryForObject("select estimated_amount from billing_ai_usage where scope='image_tool'",java.math.BigDecimal.class)).isNull();
        assertThat(jdbc.queryForObject("select operation_id from billing_ai_attempts",String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from billing_reservations",Integer.class)).isZero();
    }
    @Test void recoveryAttemptAndStaleProviderCallsAreDistinctEvenForSameResponseId() throws Exception {
        var c=ResponsesUsageTest.context();save(c,call(c),"stale");
        var recovered=new AiCallContext(new AiCallContext.Identity("analysis_jobs","job_test",2,null,"free_operation"),CLOCK);
        save(recovered,call(recovered),"completed");
        assertThat(jdbc.queryForList("select job_attempt from billing_ai_attempts order by job_attempt",Integer.class)).containsExactly(1,2);
        assertThat(jdbc.queryForList("select provider_outcome from billing_ai_attempts",String.class)).containsOnly("successful");
        assertThat(jdbc.queryForList("select job_outcome from billing_ai_attempts",String.class)).containsExactlyInAnyOrder("stale","completed");
    }
    @Test void noUsageAndStubKeepNullCost() {
        var c=ResponsesUsageTest.context();c.fallback("test",null,null,true,()->1);save(c,c.calls().getFirst(),"completed");
        assertThat(jdbc.queryForObject("select cost_state from billing_ai_usage",String.class)).isEqualTo("stub");
        assertThat(jdbc.queryForObject("select is_stub from billing_ai_attempts",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select estimated_amount from billing_ai_usage",java.math.BigDecimal.class)).isNull();
        assertThat(jdbc.queryForObject("select counters::text from billing_ai_usage",String.class)).isEqualTo("{}");
    }
    @Test void transactionalRollbackCanRetrySameSnapshotWithoutPartialRows() throws Exception {
        var c=ResponsesUsageTest.context();var call=call(c);
        tx.executeWithoutResult(t->{store.save(c.identity,call,"completed");t.setRollbackOnly();});
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts",Integer.class)).isZero();
        save(c,call,"completed");
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_usage",Integer.class)).isEqualTo(2);
    }
    void rate(String version,String effective,String amount) {
        for(String category:List.of("input_uncached","input_cached","input_cache_write","output"))
            jdbc.update("""
                    insert into billing_provider_rates(provider,model,scope,version,category,currency,amount_per_million,effective_from,source_url)
                    values('openai','main-actual-2026-10-01','response',?,?,'USD',?::numeric,?::timestamptz,'https://example.test/fake-rates')
                    on conflict do nothing
                    """,version,category,amount,effective);
    }
    @Test void pinsEffectiveExactModelRateVersionAndNeverPricesToolOrChangesCustomerCatalog() throws Exception {
        rate("fake-old","2026-10-01T00:00:00Z","2");rate("fake-future","2026-11-01T00:00:00Z","99");
        var before=jdbc.queryForList("select * from billing_prices");
        var c=ResponsesUsageTest.context();var call=call(c);save(c,call,"completed");
        assertThat(jdbc.queryForObject("select rate_version from billing_ai_usage where scope='response'",String.class)).isEqualTo("fake-old");
        assertThat(jdbc.queryForObject("select estimated_amount from billing_ai_usage where scope='response'",java.math.BigDecimal.class)).isEqualByComparingTo("0.000300");
        assertThat(jdbc.queryForObject("select cost_state from billing_ai_usage where scope='image_tool'",String.class)).isEqualTo("unknown");
        assertThat(jdbc.queryForList("select * from billing_prices")).isEqualTo(before);
        assertThatThrownBy(()->jdbc.update("update billing_provider_rates set amount_per_million=0 where version='fake-old'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
