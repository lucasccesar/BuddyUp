package com.buddyup.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestPropertySource(properties = "management.endpoint.health.show-components=always")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BuddyUpApplicationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("[migrate] Should Create BuddyUp Schema - When Application Starts With Empty PostgreSQL")
	void shouldCreateBuddyUpSchemaWhenApplicationStartsWithEmptyPostgres() throws Exception {
		try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var statement = connection.createStatement();
				var result = statement.executeQuery("""
						select exists(select from pg_namespace where nspname = 'buddyup') as schema_exists,
						       exists(select from public.flyway_schema_history
						              where version = '1' and success) as initial_migration_applied
						""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getBoolean("schema_exists")).isTrue();
			assertThat(result.getBoolean("initial_migration_applied")).isTrue();
		}
	}

	@Test
	@DisplayName("[health] Should Report Database Up - When PostgreSQL Is Reachable")
	void shouldReportDatabaseUpWhenPostgresIsReachable() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/db"))
				.timeout(Duration.ofSeconds(10))
				.build();

		try (var client = HttpClient.newHttpClient()) {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}
	}
}
