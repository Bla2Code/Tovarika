package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Opt-in cross-repository browser acceptance against real HTTP, Security and PostgreSQL. */
@Testcontainers
@EnabledIfEnvironmentVariable(named="TOVARIKA_UI_E2E",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.docker.compose.enabled=false", "tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false",
    "tovarika.security.cors.allowed-origins=http://localhost:5175",
    "tovarika.assets.public-base-url=http://localhost:5175", "tovarika.ai.openai.mode=stub",
    "tovarika.analysis.worker-enabled=true", "tovarika.cards.worker-enabled=true",
    "tovarika.billing.ai-admission-enabled=true"
})
@Import(CardSeriesStorageConfiguration.class)
class CardSeriesBrowserIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Test void browserCreatesAndContinuesSeriesThroughRealBackend() throws Exception {
        // Test-only allowance funds two paid images. Production seed remains 600 units.
        tx.executeWithoutResult(t -> {
            jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values('billing-browser-fixture',1000,1,clock_timestamp(),'isolated browser verification')");
            jdbc.update("insert into billing_prices select 'billing-browser-fixture',operation_code,amount_units from billing_prices where version_id='billing-2026-10-14-v1'");
            jdbc.update("update billing_price_versions set published_at=clock_timestamp() where id='billing-browser-fixture'");
            jdbc.update("update billing_configuration set active_version='billing-browser-fixture'");
        });
        String directory=System.getenv("TOVARIKA_UI_DIRECTORY");
        assertThat(directory).as("Set TOVARIKA_UI_DIRECTORY to the UI checkout").isNotBlank();
        var ui=Path.of(directory);
        var process=new ProcessBuilder("node",ui.resolve("scripts/check-card-series-backend.mjs").toString(),"http://127.0.0.1:"+port)
                .directory(ui.toFile()).redirectErrorStream(true).redirectOutput(Path.of("/tmp/card-series-real-browser.log").toFile()).start();
        try { assertThat(process.waitFor(180,TimeUnit.SECONDS)).as("Browser acceptance completed").isTrue();
              assertThat(process.exitValue()).as("See /tmp/card-series-real-browser.log for browser diagnostics").isZero(); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
    }
}
