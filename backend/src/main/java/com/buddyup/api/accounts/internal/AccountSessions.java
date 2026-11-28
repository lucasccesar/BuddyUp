package com.buddyup.api.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountSessions {
	private final JdbcTemplate jdbc;
	private final RegistrationSecrets secrets;
	private final PasswordEncoder passwords;
	private final Clock clock;
	private final String fallbackHash;

	AccountSessions(JdbcTemplate jdbc, RegistrationSecrets secrets, PasswordEncoder passwords, Clock clock) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.passwords = passwords;
		this.clock = clock;
		this.fallbackHash = passwords.encode(secrets.newProof());
	}

	@Transactional
	Login login(String email, String password, Channel channel, boolean rememberMe) {
		var accounts = jdbc.query("""
				select id, password_hash, session_epoch from buddyup.account
				where email_normalized = ? and state = 'ACTIVE' and email_verified_at is not null
				and password_hash is not null for update
				""", (row, index) -> new LoginAccount(row.getObject(1, UUID.class), row.getString(2), row.getLong(3)), email);
		var account = accounts.stream().findFirst().orElse(null);
		boolean matches = passwords.matches(password, account == null ? fallbackHash : account.passwordHash());
		if (account == null || !matches) {
			throw RegistrationFailure.invalidProof();
		}
		String token = secrets.newProof();
		jdbc.update("""
				insert into buddyup.account_session
				    (id, account_id, token_digest, channel, remember_me, session_epoch, last_seen_at)
				values (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), account.id(), secrets.proofDigest(token), channel.name(), rememberMe,
				account.epoch(), OffsetDateTime.now(clock));
		return new Login(token, new SessionContext(account.id(), "ACTIVE", rememberMe, List.of(),
				channel == Channel.WEB ? csrf(token) : null));
	}

	@Transactional
	SessionContext current(Credential credential) {
		var session = require(credential);
		recordActivity(session.id());
		return new SessionContext(session.accountId(), session.stage(), session.rememberMe(), List.of(),
				credential.channel() == Channel.WEB ? csrf(credential.token()) : null);
	}

	@Transactional
	void logout(Credential credential) {
		var session = lockActive(credential);
		jdbc.update("update buddyup.account_session set revoked_at = ? where id = ?",
				OffsetDateTime.now(clock), session.id());
	}

	ActiveSession lockActive(Credential credential) {
		var session = require(credential);
		jdbc.query("select id from buddyup.account where id = ? for update", row -> { }, session.accountId());
		var locked = require(credential);
		recordActivity(locked.id());
		return locked;
	}

	private void recordActivity(UUID sessionId) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		jdbc.update("update buddyup.account_session set last_seen_at = ? where id = ? and last_seen_at < ?",
				now, sessionId, now.minusMinutes(5));
	}

	private ActiveSession require(Credential credential) {
		if (credential == null || !credential.token().matches("[A-Za-z0-9_-]{43}")) {
			throw RegistrationFailure.sessionRequired();
		}
		var session = jdbc.query("""
				select s.id, s.account_id, s.remember_me, a.state, a.email_normalized, a.password_hash,
				    s.revoked_at is not null or s.session_epoch <> a.session_epoch
				        or a.state not in ('ACTIVE', 'PROFILE_REQUIRED') as revoked,
				    not s.remember_me and s.last_seen_at <= ? as expired
				from buddyup.account_session s join buddyup.account a on a.id = s.account_id
				where s.token_digest = ? and s.channel = ?
				""", (row, index) -> new ActiveSession(row.getObject("id", UUID.class),
				row.getObject("account_id", UUID.class), row.getBoolean("remember_me"), row.getString("state"),
				row.getString("email_normalized"), row.getString("password_hash"), row.getBoolean("revoked"),
				row.getBoolean("expired")), OffsetDateTime.now(clock).minusHours(24), secrets.proofDigest(credential.token()),
				credential.channel().name()).stream().findFirst().orElseThrow(RegistrationFailure::sessionRequired);
		if (session.revoked()) {
			throw RegistrationFailure.sessionRevoked();
		}
		if (session.expired()) {
			throw RegistrationFailure.sessionRequired();
		}
		return session;
	}

	boolean matchesCsrf(String sessionToken, String supplied) {
		return supplied != null && MessageDigest.isEqual(csrf(sessionToken).getBytes(StandardCharsets.UTF_8),
				supplied.getBytes(StandardCharsets.UTF_8));
	}

	private String csrf(String sessionToken) {
		return secrets.proofDigest("web-csrf:" + sessionToken);
	}

	static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	enum Channel { WEB, MOBILE }

	record Credential(String token, Channel channel) {}

	record ActiveSession(UUID id, UUID accountId, boolean rememberMe, String stage, String email,
			String passwordHash, boolean revoked, boolean expired) {}

	record SessionContext(UUID accountId, String accountStage, boolean rememberMe, List<String> roles,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
			String csrfToken) {}

	record Login(String token, SessionContext session) {}

	private record LoginAccount(UUID id, String passwordHash, long epoch) {}
}
