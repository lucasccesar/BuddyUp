package com.buddyup.api.accounts.internal;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.buddyup.api.accounts.internal.RegistrationConfiguration.Settings;

@Service
class RegistrationService {
	private static final Duration REQUEST_WINDOW = Duration.ofHours(1);
	private final JdbcTemplate jdbc;
	private final RegistrationSecrets secrets;
	private final PasswordEncoder passwords;
	private final Clock clock;
	private final Settings settings;

	RegistrationService(JdbcTemplate jdbc, RegistrationSecrets secrets, PasswordEncoder passwords, Clock clock,
			Settings settings) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.passwords = passwords;
		this.clock = clock;
		this.settings = settings;
	}

	@Transactional
	IssuedChallenge start(String email, String remoteAddress) {
		String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		String source = secrets.sourceDigest(remoteAddress);
		lock("registration-source:" + source);
		lock("registration-email:" + normalizedEmail);
		OffsetDateTime now = OffsetDateTime.now(clock);
		checkRequestLimits(normalizedEmail, source, now);

		boolean existing = Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists(select from buddyup.account where email_normalized = ?)", Boolean.class, normalizedEmail));
		jdbc.update("""
				update buddyup.auth_challenge set consumed_at = ?
				where kind = 'REGISTRATION_EMAIL' and email_normalized = ? and consumed_at is null and expires_at > ?
				""", now, normalizedEmail, now);
		UUID id = UUID.randomUUID();
		String code = secrets.newCode();
		jdbc.update("""
				insert into buddyup.auth_challenge
				    (id, email_normalized, kind, secret_digest, request_source_digest, created_at, expires_at, consumed_at)
				values (?, ?, 'REGISTRATION_EMAIL', ?, ?, ?, ?, ?)
				""", id, normalizedEmail, secrets.codeDigest(id, code), source, now, now.plus(settings.codeLifetime()),
				existing ? now : null);
		return new IssuedChallenge(id, normalizedEmail, existing ? null : code);
	}

	void cancelDelivery(UUID id) {
		jdbc.update("update buddyup.auth_challenge set consumed_at = ? where id = ? and consumed_at is null",
				OffsetDateTime.now(clock), id);
	}

	@Transactional(noRollbackFor = RegistrationFailure.class)
	String confirmEmail(UUID id, String code) {
		var challenges = jdbc.query("""
				select email_normalized, secret_digest, expires_at, consumed_at, attempt_count
				from buddyup.auth_challenge where id = ? and kind = 'REGISTRATION_EMAIL' for update
				""", (row, index) -> new EmailChallenge(row.getString("email_normalized"), row.getString("secret_digest"),
				row.getObject("expires_at", OffsetDateTime.class), row.getObject("consumed_at", OffsetDateTime.class),
				row.getInt("attempt_count")), id);
		OffsetDateTime now = OffsetDateTime.now(clock);
		EmailChallenge challenge = challenges.stream().findFirst().orElseThrow(RegistrationFailure::invalidProof);
		if (challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)) {
			throw RegistrationFailure.invalidProof();
		}
		if (challenge.attempts() >= settings.maxCodeAttempts()) {
			throw RegistrationFailure.limited(secondsUntil(now, challenge.expiresAt()));
		}
		if (!secrets.matchesCode(id, code, challenge.digest())) {
			jdbc.update("update buddyup.auth_challenge set attempt_count = attempt_count + 1 where id = ?", id);
			throw RegistrationFailure.invalidProof();
		}

		String token = secrets.newProof();
		jdbc.update("""
				insert into buddyup.auth_challenge (id, email_normalized, kind, secret_digest, created_at, expires_at)
				values (?, ?, 'REGISTRATION_PROOF', ?, ?, ?)
				""", UUID.randomUUID(), challenge.email(), secrets.proofDigest(token), now, now.plus(settings.proofLifetime()));
		jdbc.update("update buddyup.auth_challenge set consumed_at = ? where id = ?", now, id);
		return token;
	}

	@Transactional
	void completeRegistration(String registrationToken, String password, RegistrationProfile profile) {
		var proofs = jdbc.query("""
				select id, email_normalized, created_at, expires_at, consumed_at from buddyup.auth_challenge
				where kind = 'REGISTRATION_PROOF' and secret_digest = ? for update
				""", (row, index) -> new Proof(row.getObject("id", UUID.class), row.getString("email_normalized"),
				row.getObject("created_at", OffsetDateTime.class), row.getObject("expires_at", OffsetDateTime.class),
				row.getObject("consumed_at", OffsetDateTime.class)), secrets.proofDigest(registrationToken));
		var proof = proofs.stream().findFirst().orElseThrow(RegistrationFailure::invalidProof);
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (proof.consumedAt() != null || !proof.expiresAt().isAfter(now)) {
			throw RegistrationFailure.invalidProof();
		}

		UUID accountId = UUID.randomUUID();
		int insertedAccount = jdbc.update("""
				insert into buddyup.account
				    (id, email_normalized, email_verified_at, password_hash, state, adult_declared_at, created_at)
				values (?, ?, ?, ?, 'ACTIVE', ?, ?)
				on conflict on constraint uq_account_email do nothing
				""", accountId, proof.email(), proof.confirmedAt(), passwords.encode(password), now, now);
		if (insertedAccount == 0) {
			throw RegistrationFailure.conflict("ACCOUNT_DATA_CONFLICT");
		}
		String nickname = Normalizer.normalize(profile.nickname().strip(), Normalizer.Form.NFC);
		int insertedProfile = jdbc.update("""
				insert into buddyup.player_profile (account_id, display_name, nickname, nickname_key, country_or_region_code)
				values (?, ?, ?, ?, ?)
				on conflict on constraint uq_player_profile_nickname do nothing
				""", accountId, profile.name().strip(), nickname, nickname.toLowerCase(Locale.ROOT), profile.region().strip());
		if (insertedProfile == 0) {
			throw RegistrationFailure.conflict("NICKNAME_UNAVAILABLE");
		}
		var languages = profile.languages().stream().map(language -> Locale.forLanguageTag(language).toLanguageTag())
				.distinct().toList();
		jdbc.batchUpdate("insert into buddyup.profile_language (profile_id, language_code) values (?, ?)", languages,
				languages.size(), (statement, language) -> {
					statement.setObject(1, accountId);
					statement.setString(2, language);
				});
		jdbc.update("update buddyup.auth_challenge set consumed_at = ?, account_id = ? where id = ?",
				now, accountId, proof.id());
	}

	private void lock(String key) {
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", row -> { }, key);
	}

	private void checkRequestLimits(String email, String source, OffsetDateTime now) {
		OffsetDateTime windowStart = now.minus(REQUEST_WINDOW);
		var byEmail = jdbc.queryForObject("""
				select count(*) as requests, min(created_at) as oldest, max(created_at) as latest
				from buddyup.auth_challenge where kind = 'REGISTRATION_EMAIL' and email_normalized = ? and created_at > ?
				""", (row, index) -> new RequestWindow(row.getLong("requests"), row.getObject("oldest", OffsetDateTime.class),
				row.getObject("latest", OffsetDateTime.class)), email, windowStart);
		if (byEmail.count() >= settings.maxRequestsPerEmail()) {
			throw RegistrationFailure.limited(secondsUntil(now, byEmail.oldest().plus(REQUEST_WINDOW)));
		}
		if (byEmail.latest() != null && byEmail.latest().plus(settings.resendDelay()).isAfter(now)) {
			throw RegistrationFailure.limited(secondsUntil(now, byEmail.latest().plus(settings.resendDelay())));
		}
		var bySource = jdbc.queryForObject("""
				select count(*) as requests, min(created_at) as oldest, max(created_at) as latest
				from buddyup.auth_challenge where kind = 'REGISTRATION_EMAIL' and request_source_digest = ?
				    and created_at > ?
				""", (row, index) -> new RequestWindow(row.getLong("requests"), row.getObject("oldest", OffsetDateTime.class),
				row.getObject("latest", OffsetDateTime.class)), source, windowStart);
		if (bySource.count() >= settings.maxRequestsPerSource()) {
			throw RegistrationFailure.limited(secondsUntil(now, bySource.oldest().plus(REQUEST_WINDOW)));
		}
	}

	private long secondsUntil(OffsetDateTime now, OffsetDateTime end) {
		Duration wait = Duration.between(now, end);
		return Math.max(1, wait.toSeconds() + (wait.getNano() == 0 ? 0 : 1));
	}

	record IssuedChallenge(UUID id, String email, String code) {}

	private record EmailChallenge(String email, String digest, OffsetDateTime expiresAt, OffsetDateTime consumedAt,
			int attempts) {}

	private record Proof(UUID id, String email, OffsetDateTime confirmedAt, OffsetDateTime expiresAt,
			OffsetDateTime consumedAt) {}

	private record RequestWindow(long count, OffsetDateTime oldest, OffsetDateTime latest) {}
}
