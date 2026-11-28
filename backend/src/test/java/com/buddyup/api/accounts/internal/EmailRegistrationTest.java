package com.buddyup.api.accounts.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailRegistrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Container
	static final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.34.0"))
			.withExposedPorts(1025, 8025);

	@DynamicPropertySource
	static void registrationProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.mail.host", mailpit::getHost);
		registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
		registry.add("buddyup.registration.code-key", () -> "test-only-registration-key-0123456789");
		registry.add("buddyup.registration.from", () -> "registration@example.test");
	}

	@LocalServerPort
	int port;

	@Autowired ObjectMapper json;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	@Test
	@DisplayName("[confirmEmail] Should Reject A Wrong Code - When A Challenge Exists")
	void shouldRejectWrongCodeWhenChallengeExists() throws Exception {
		var challenge = challenge();
		String wrong = challenge.code().equals("000000") ? "111111" : "000000";
		for (int attempt = 0; attempt < 5; attempt++) {
			assertStatus(confirm(challenge.id(), wrong), 401, "AUTHENTICATION_FAILED");
		}
		assertStatus(confirm(challenge.id(), challenge.code()), 429, "TOO_MANY_ATTEMPTS");
	}

	@Test
	@DisplayName("[confirmEmail] Should Reject A Valid Code - When The Challenge Has Expired")
	void shouldRejectValidCodeWhenChallengeHasExpired() throws Exception {
		var challenge = challenge();
		jdbc.update("update buddyup.auth_challenge set expires_at = now() - interval '1 second' where id = ?",
				UUID.fromString(challenge.id()));
		assertStatus(confirm(challenge.id(), challenge.code()), 401, "AUTHENTICATION_FAILED");
	}

	@Test
	@DisplayName("[startEmailRegistration] Should Replace The Old Code - When Resend Becomes Available")
	void shouldReplaceOldCodeWhenResendBecomesAvailable() throws Exception {
		var first = challenge();
		var limited = post("/email-challenges", Map.of("email", first.email()));
		assertStatus(limited, 429, "TOO_MANY_ATTEMPTS");
		assertThat(limited.headers().firstValue("Retry-After")).isPresent();
		jdbc.update("update buddyup.auth_challenge set created_at = now() - interval '61 seconds' where id = ?",
				UUID.fromString(first.id()));
		var resend = post("/email-challenges", Map.of("email", first.email().toUpperCase(java.util.Locale.ROOT)));
		assertThat(resend.statusCode()).isEqualTo(202);
		assertThat(field(resend, "challengeId")).isNotEqualTo(first.id());
		assertStatus(confirm(first.id(), first.code()), 401, "AUTHENTICATION_FAILED");
		assertThat(confirm(field(resend, "challengeId"), findCode(first.email())).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("[completeRegistration] Should Reject A Registration Proof - When It Has Expired")
	void shouldRejectRegistrationProofWhenItHasExpired() throws Exception {
		var challenge = challenge();
		String token = field(confirm(challenge.id(), challenge.code()), "registrationToken");
		jdbc.update("""
				update buddyup.auth_challenge set expires_at = now() - interval '1 second'
				where email_normalized = ? and kind = 'REGISTRATION_PROOF'
				""", challenge.email());
		assertStatus(post("", registration(token, "PlayerExpired", true)), 401, "AUTHENTICATION_FAILED");
		assertThat(jdbc.queryForObject("select count(*) from buddyup.account where email_normalized = ?",
				Integer.class, challenge.email())).isZero();
	}

	@Test
	@DisplayName("[completeRegistration] Should Resume From Email Proof - When The Code Was Already Used")
	void shouldResumeFromEmailProofWhenCodeWasAlreadyUsed() throws Exception {
		var challenge = challenge();
		var proof = confirm(challenge.id(), challenge.code());
		assertThat(proof.statusCode()).isEqualTo(200);
		String token = field(proof, "registrationToken");
		assertStatus(confirm(challenge.id(), challenge.code()), 401, "AUTHENTICATION_FAILED");
		assertThat(post("", registration(token, "PlayerResume", true)).statusCode()).isEqualTo(201);
		assertStatus(post("", registration(token, "PlayerOther", true)), 401, "AUTHENTICATION_FAILED");
		String hash = jdbc.queryForObject("select password_hash from buddyup.account where email_normalized = ?",
				String.class, challenge.email());
		assertThat(hash).isNotEqualTo("test-password-2026");
		assertThat(passwords.matches("test-password-2026", hash)).isTrue();
	}

	@Test
	@DisplayName("[completeRegistration] Should Preserve The Draft - When Adult Declaration Is Missing")
	void shouldPreserveDraftWhenAdultDeclarationIsMissing() throws Exception {
		var challenge = challenge();
		String token = field(confirm(challenge.id(), challenge.code()), "registrationToken");
		var data = registration(token, "PlayerAdult", false);
		((Map<?, ?>) data.get("profile")).remove("confirmsAdultAge");
		assertStatus(post("", data), 400, "VALIDATION_FAILED");
		assertThat(post("", registration(token, "PlayerAdult", true)).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("[completeRegistration] Should Reserve Nickname Once - When Accounts Register Concurrently")
	void shouldReserveNicknameOnceWhenAccountsRegisterConcurrently() throws Exception {
		var first = challenge();
		var second = challenge();
		String firstToken = field(confirm(first.id(), first.code()), "registrationToken");
		String secondToken = field(confirm(second.id(), second.code()), "registrationToken");

		try (var client = HttpClient.newHttpClient()) {
			var firstResult = client.sendAsync(request("", registration(firstToken, "AnaFPS", true)),
					HttpResponse.BodyHandlers.ofString());
			var secondResult = client.sendAsync(request("", registration(secondToken, "anafps", true)),
					HttpResponse.BodyHandlers.ofString());
			CompletableFuture.allOf(firstResult, secondResult).join();
			var results = java.util.List.of(firstResult.join(), secondResult.join());
			assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
			assertStatus(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
					409, "NICKNAME_UNAVAILABLE");
			assertThat(jdbc.queryForObject("select count(*) from buddyup.player_profile where nickname_key = 'anafps'",
					Integer.class)).isEqualTo(1);
			String retryToken = firstResult.join().statusCode() == 409 ? firstToken : secondToken;
			assertThat(post("", registration(retryToken, "AnaFPS2", true)).statusCode()).isEqualTo(201);
		}
	}

	private Challenge challenge() throws Exception {
		String email = UUID.randomUUID() + "@example.test";
		var response = post("/email-challenges", Map.of("email", email));
		assertThat(response.statusCode()).isEqualTo(202);
		return new Challenge(email, field(response, "challengeId"), findCode(email));
	}

	private String findCode(String email) throws Exception {
		String mailbox = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1";
		try (var client = HttpClient.newHttpClient()) {
			var messages = json.readTree(get(client, mailbox + "/messages")).path("messages");
			for (var message : messages) {
				if (email.equals(message.path("To").path(0).path("Address").asText())) {
					var mail = json.readTree(get(client, mailbox + "/message/" + message.path("ID").asText()));
					var code = Pattern.compile("\\b[0-9]{6}\\b").matcher(mail.path("Text").asText());
					assertThat(code.find()).isTrue();
					return code.group();
				}
			}
		}
		throw new AssertionError("Expected a registration email");
	}

	private String get(HttpClient client, String url) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
	}

	private HttpResponse<String> confirm(String id, String code) throws Exception {
		return post("/email-confirmations", Map.of("challengeId", id, "code", code));
	}

	private HttpResponse<String> post(String suffix, Object body) throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			return client.send(request(suffix, body), HttpResponse.BodyHandlers.ofString());
		}
	}

	private HttpRequest request(String suffix, Object body) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/auth/registration" + suffix))
				.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
	}

	private String field(HttpResponse<String> response, String field) {
		return json.readTree(response.body()).path(field).asText();
	}

	private void assertStatus(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				contentType -> assertThat(contentType).startsWith("application/problem+json"));
		assertThat(field(response, "code")).isEqualTo(code);
	}

	private Map<String, Object> registration(String token, String nickname, boolean adult) {
		var profile = new java.util.HashMap<>(Map.of("name", "Ana", "nickname", nickname, "region", "BR",
				"languages", java.util.List.of("pt-BR"), "confirmsAdultAge", adult));
		return Map.of("registrationToken", token, "password", "test-password-2026", "profile", profile);
	}

	private record Challenge(String email, String id, String code) {}
}
