package com.buddyup.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestPropertySource(properties = "management.endpoint.health.show-components=always")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
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
	@DisplayName("[migrate] Should Preserve Account And Registration Proof - When Upgrading From Version Two")
	void shouldPreserveAccountAndRegistrationProofWhenUpgradingFromVersionTwo() throws Exception {
		try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("create database buddyup_upgrade");
		}
		String upgradeUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
				+ "/buddyup_upgrade";
		Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword())
				.defaultSchema("public").target("2").load().migrate();
		UUID accountId = UUID.randomUUID();
		UUID challengeId = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(upgradeUrl, postgres.getUsername(), postgres.getPassword());
				var insertAccount = connection.prepareStatement("""
						insert into buddyup.account (id, email_normalized, state, created_at)
						values (?, 'existing@example.test', 'ACTIVE', now())
						""");
				var insertProof = connection.prepareStatement("""
						insert into buddyup.auth_challenge (id, email_normalized, kind, secret_digest, created_at, expires_at)
						values (?, 'pending@example.test', 'REGISTRATION_PROOF', 'test-digest', now(), now() + interval '1 hour')
						""")) {
			insertAccount.setObject(1, accountId);
			insertAccount.executeUpdate();
			insertProof.setObject(1, challengeId);
			insertProof.executeUpdate();
		}
		var migration = Flyway.configure().dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword())
				.defaultSchema("public").load().migrate();
		assertThat(migration.targetSchemaVersion).isEqualTo("3");
		try (var connection = DriverManager.getConnection(upgradeUrl, postgres.getUsername(), postgres.getPassword());
				var query = connection.prepareStatement("""
						select a.email_normalized, c.kind, c.consumed_at
						from buddyup.account a cross join buddyup.auth_challenge c
						where a.id = ? and c.id = ?
						""")) {
			query.setObject(1, accountId);
			query.setObject(2, challengeId);
			try (var row = query.executeQuery()) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString(1)).isEqualTo("existing@example.test");
				assertThat(row.getString(2)).isEqualTo("REGISTRATION_PROOF");
				assertThat(row.getObject(3)).isNull();
			}
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
