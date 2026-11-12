package dev.minibank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void healthIsUpAndIncludesDatabase() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"db\"");
	}

	@Test
	void baselineMigrationApplied() {
		Integer applied = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '001' AND success",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

}
