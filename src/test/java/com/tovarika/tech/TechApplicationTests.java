package com.tovarika.tech;

import static org.assertj.core.api.Assertions.assertThat;

import com.tovarika.tech.templates.TemplateService;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
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

	@Autowired
	TemplateService templates;

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

	@Test
	void fashionExampleIsTheOnlyConfiguredReferenceAndMatchesTheUploadFile() throws Exception {
		var template = templates.generationTemplate("tpl_fashion_hero").orElseThrow();
		assertThat(template.hasReference()).isTrue();
		assertThat(template.referenceMediaType()).isEqualTo("image/webp");

		var file = Path.of("assets").resolve(template.referenceStorageKey());
		var image = ImageIO.read(file.toFile());
		assertThat(image).isNotNull();
		assertThat(image.getWidth()).isEqualTo(384);
		assertThat(image.getHeight()).isEqualTo(512);
		assertThat(Files.size(file)).isLessThan(100 * 1024L);
		var dimensions = jdbcClient.sql("SELECT size_bytes, width, height FROM assets WHERE id = :id")
				.param("id", template.referenceAssetId())
				.query((result, row) -> new int[] {
						result.getInt("size_bytes"), result.getInt("width"), result.getInt("height")
				})
				.single();
		assertThat(dimensions).containsExactly((int) Files.size(file), image.getWidth(), image.getHeight());

		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM templates WHERE reference_asset_id IS NOT NULL")
				.query(Integer.class).single()).isEqualTo(1);
		assertThat(templates.list(null, "cat_fashion", null, false, null, 20).items())
				.filteredOn(item -> item.referenceAssetId() != null)
				.singleElement().satisfies(item -> {
					assertThat(item.id()).isEqualTo(template.id());
					assertThat(item.name()).isEqualTo("Крупный заголовок и характеристики");
				});
	}

}
