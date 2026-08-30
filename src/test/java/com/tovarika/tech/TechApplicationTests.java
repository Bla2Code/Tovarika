package com.tovarika.tech;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
		"spring.docker.compose.enabled=false",
		"tovarika.storage.minio.initialize-bucket=false",
		"tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"tovarika.security.password.breached-check-enabled=false"
})
final class TechApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void contextLoadsAndLiquibaseMigratesPostgres() {
		var applied = jdbcClient.sql("""
				SELECT COUNT(*)
				FROM databasechangelog
				WHERE id = '000-bootstrap'
				""")
				.query(Integer.class)
				.single();

		assertThat(applied).isEqualTo(1);
	}

}
