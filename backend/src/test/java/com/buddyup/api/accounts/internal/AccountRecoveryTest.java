package com.buddyup.api.accounts.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext
class AccountRecoveryTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Container
	static final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.34.0"))
			.withExposedPorts(1025, 8025);

	private static final String ORIGINAL_PASSWORD = "original-test-password";
	private static final String REPLACEMENT_PASSWORD = "replacement-test-password";
	private static final String WEB_ORIGIN = "https://buddyup.example.test";

	@DynamicPropertySource
	static void recoveryProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.mail.host", mailpit::getHost);
		registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
		registry.add("buddyup.registration.code-key", () -> "test-only-registration-key-0123456789");
		registry.add("buddyup.registration.from", () -> "recovery@example.test");
		registry.add("buddyup.accounts.web-origin", () -> WEB_ORIGIN);
		registry.add("buddyup.accounts.password-reset-url", () -> WEB_ORIGIN + "/redefinir-senha");
	}

	@LocalServerPort int port;
	@Autowired ObjectMapper json;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	@Test
	@DisplayName("[resetPassword] Should Reject A Reset Token - When It Has Expired")
	void shouldRejectResetTokenWhenItHasExpired() throws Exception {
		var account = account();
		String token = resetToken(account.email());
		jdbc.update("""
				update buddyup.auth_challenge set expires_at = now() - interval '1 second'
				where account_id = ? and kind = 'PASSWORD_RESET'
				""", account.id());
		assertProblem(reset(token), 401, "AUTHENTICATION_FAILED");
		assertThat(login(account.email(), ORIGINAL_PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("[resetPassword] Should Revoke Every Old Session And Reject Reuse - When Password Is Reset")
	void shouldRevokeEveryOldSessionAndRejectReuseWhenPasswordIsReset() throws Exception {
		var account = account();
		String first = loginToken(account);
		String second = loginToken(account);
		assertThat(session(first).statusCode()).isEqualTo(200);
		String token = resetToken(account.email());
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var firstReset = executor.submit(() -> reset(token).statusCode());
			var secondReset = executor.submit(() -> reset(token).statusCode());
			assertThat(java.util.List.of(firstReset.get(), secondReset.get())).containsExactlyInAnyOrder(204, 401);
		}
		assertProblem(reset(token), 401, "AUTHENTICATION_FAILED");
		assertProblem(session(first), 401, "SESSION_REVOKED");
		assertProblem(session(second), 401, "SESSION_REVOKED");
		assertProblem(login(account.email(), ORIGINAL_PASSWORD), 401, "AUTHENTICATION_FAILED");
		var login = login(account.email(), REPLACEMENT_PASSWORD);
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(json.readTree(login.body()).path("session").path("accountId").asText())
				.isEqualTo(account.id().toString());
	}

	@Test
	@DisplayName("[requestPasswordReset] Should Return The Same Response - When The Email Has No Account")
	void shouldReturnSameResponseWhenEmailHasNoAccount() throws Exception {
		var known = send("POST", "/v1/auth/password-reset-requests", Map.of("email", account().email()), Map.of());
		String unknownEmail = randomEmail();
		var unknown = send("POST", "/v1/auth/password-reset-requests", Map.of("email", unknownEmail), Map.of());
		assertThat(known.statusCode()).isEqualTo(202);
		assertThat(unknown.statusCode()).isEqualTo(known.statusCode());
		assertThat(unknown.body()).isEqualTo(known.body()).isEmpty();
		assertThat(jdbc.queryForObject("""
				select count(*) from buddyup.auth_challenge where email_normalized = ? and kind = 'PASSWORD_RESET'
				""", Integer.class, unknownEmail)).isZero();
	}

	@Test
	@DisplayName("[confirmEmailChange] Should Update The Same Account And Notify The Old Address - When The New Address Is Verified")
	void shouldUpdateAccountAndNotifyOldAddressWhenNewAddressIsVerified() throws Exception {
		var account = account();
		String session = loginToken(account);
		String otherSession = loginToken(account);
		String oldResetToken = resetToken(account.email());
		String newEmail = randomEmail();
		assertProblem(send("POST", "/v1/auth/reauthentications",
				Map.of("purpose", "CHANGE_EMAIL", "password", REPLACEMENT_PASSWORD), bearer(session)),
				401, "AUTHENTICATION_FAILED");
		var reauth = send("POST", "/v1/auth/reauthentications",
				Map.of("purpose", "CHANGE_EMAIL", "password", ORIGINAL_PASSWORD), bearer(session));
		assertThat(reauth.statusCode()).isEqualTo(200);
		String proof = field(reauth, "reauthenticationToken");
		assertProblem(requestEmailChange(newEmail, otherSession, proof), 403, "REAUTHENTICATION_REQUIRED");
		var challenge = requestEmailChange(newEmail, session, proof);
		assertThat(challenge.statusCode()).isEqualTo(202);
		assertProblem(requestEmailChange(randomEmail(), session, proof), 403, "REAUTHENTICATION_REQUIRED");

		String id = field(challenge, "challengeId");
		String code = match(mail(newEmail, "Confirmação de novo e-mail"), "\\b([0-9]{6})\\b");
		String wrong = code.equals("000000") ? "111111" : "000000";
		assertProblem(confirmChange(session, id, wrong), 401, "AUTHENTICATION_FAILED");
		assertProblem(confirmChange(otherSession, id, code), 401, "AUTHENTICATION_FAILED");
		assertThat(currentEmail(account)).isEqualTo(account.email());
		assertThat(confirmChange(session, id, code).statusCode()).isEqualTo(204);
		assertProblem(confirmChange(session, id, code), 401, "AUTHENTICATION_FAILED");
		assertThat(currentEmail(account)).isEqualTo(newEmail);
		assertThat(mail(account.email(), "E-mail da conta alterado")).contains(newEmail);
		assertProblem(reset(oldResetToken), 401, "AUTHENTICATION_FAILED");
		assertThat(jdbc.queryForObject("select display_name from buddyup.player_profile where account_id = ?",
				String.class, account.id())).isEqualTo("Player Test");
		assertProblem(login(account.email(), ORIGINAL_PASSWORD), 401, "AUTHENTICATION_FAILED");
		var login = login(newEmail, ORIGINAL_PASSWORD);
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(json.readTree(login.body()).path("session").path("accountId").asText())
				.isEqualTo(account.id().toString());
	}

	@Test
	@DisplayName("[createReauthentication] Should Require A Trusted Origin And CSRF Proof - When Using A Browser Session")
	void shouldRequireTrustedOriginAndCsrfProofWhenUsingBrowserSession() throws Exception {
		var account = account();
		assertProblem(send("POST", "/v1/auth/login",
				Map.of("email", account.email(), "password", ORIGINAL_PASSWORD, "client", "MOBILE"),
				Map.of("Origin", WEB_ORIGIN)), 403, "ORIGIN_FORBIDDEN");
		var login = send("POST", "/v1/auth/login",
				Map.of("email", account.email(), "password", ORIGINAL_PASSWORD, "client", "WEB"),
				Map.of("Origin", WEB_ORIGIN));
		assertThat(login.statusCode()).isEqualTo(200);
		String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
		String csrf = json.readTree(login.body()).path("session").path("csrfToken").asText();
		var body = Map.of("purpose", "CHANGE_EMAIL", "password", ORIGINAL_PASSWORD);
		assertProblem(send("POST", "/v1/auth/reauthentications", body, Map.of("Cookie", cookie,
				"Origin", WEB_ORIGIN)), 403, "CSRF_INVALID");
		assertProblem(send("POST", "/v1/auth/reauthentications", body, Map.of("Cookie", cookie,
				"Origin", "https://another.example.test", "X-CSRF-Token", csrf)), 403, "ORIGIN_FORBIDDEN");
		assertThat(send("POST", "/v1/auth/reauthentications", body, Map.of("Cookie", cookie,
				"Origin", WEB_ORIGIN, "X-CSRF-Token", csrf)).statusCode()).isEqualTo(200);
	}

	private TestAccount account() {
		var account = new TestAccount(UUID.randomUUID(), randomEmail());
		jdbc.update("""
				insert into buddyup.account (id, email_normalized, email_verified_at, password_hash, state, created_at)
				values (?, ?, now(), ?, 'ACTIVE', now())
				""", account.id(), account.email(), passwords.encode(ORIGINAL_PASSWORD));
		jdbc.update("""
				insert into buddyup.player_profile (account_id, display_name, nickname, nickname_key, country_or_region_code)
				values (?, 'Player Test', ?, ?, 'BR')
				""", account.id(), account.id().toString(), account.id().toString());
		return account;
	}

	private String randomEmail() {
		return UUID.randomUUID() + "@example.test";
	}

	private String currentEmail(TestAccount account) {
		return jdbc.queryForObject("select email_normalized from buddyup.account where id = ?", String.class, account.id());
	}

	private String loginToken(TestAccount account) throws Exception {
		var result = login(account.email(), ORIGINAL_PASSWORD);
		assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
		return field(result, "sessionToken");
	}

	private HttpResponse<String> login(String email, String password) throws Exception {
		return send("POST", "/v1/auth/login", Map.of("email", email, "password", password, "client", "MOBILE"), Map.of());
	}

	private HttpResponse<String> session(String token) throws Exception {
		return send("GET", "/v1/auth/session", null, bearer(token));
	}

	private HttpResponse<String> requestEmailChange(String email, String session, String proof) throws Exception {
		return send("POST", "/v1/accounts/me/email-change-requests", Map.of("email", email),
				Map.of("Authorization", "Bearer " + session, "X-Reauthentication-Token", proof));
	}

	private HttpResponse<String> confirmChange(String session, String id, String code) throws Exception {
		return send("POST", "/v1/accounts/me/email-change-confirmations", Map.of("challengeId", id, "code", code),
				bearer(session));
	}

	private String resetToken(String email) throws Exception {
		assertThat(send("POST", "/v1/auth/password-reset-requests", Map.of("email", email), Map.of()).statusCode())
				.isEqualTo(202);
		return match(mail(email, "Redefinição de senha"), "/redefinir-senha#token=([A-Za-z0-9_-]{43})");
	}

	private HttpResponse<String> reset(String token) throws Exception {
		return send("POST", "/v1/auth/password-resets",
				Map.of("resetToken", token, "password", REPLACEMENT_PASSWORD), Map.of());
	}

	private Map<String, String> bearer(String token) {
		return Map.of("Authorization", "Bearer " + token);
	}

	private String match(String text, String expression) {
		var matcher = Pattern.compile(expression).matcher(text);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private String mail(String email, String subject) throws Exception {
		String mailbox = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1";
		try (var client = HttpClient.newHttpClient()) {
			for (int attempt = 0; attempt < 40; attempt++) {
				var messages = json.readTree(get(client, mailbox + "/messages")).path("messages");
				for (var message : messages) {
					if (email.equals(message.path("To").path(0).path("Address").asText())
							&& message.path("Subject").asText().contains(subject)) {
						return json.readTree(get(client, mailbox + "/message/" + message.path("ID").asText()))
								.path("Text").asText();
					}
				}
				Thread.sleep(200);
			}
		}
		throw new AssertionError("Expected a message for " + email + " with subject " + subject);
	}

	private String get(HttpClient client, String url) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
				HttpResponse.BodyHandlers.ofString()).body();
	}

	private HttpResponse<String> send(String method, String path, Object body, Map<String, String> headers)
			throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
					.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
			headers.forEach(request::header);
			return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
					HttpResponse.BodyHandlers.ofString());
		}
	}

	private void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(field(response, "code")).isEqualTo(code);
	}

	private String field(HttpResponse<String> response, String field) {
		return json.readTree(response.body()).path(field).asText();
	}

	private record TestAccount(UUID id, String email) {}
}
