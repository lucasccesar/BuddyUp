package com.buddyup.api.accounts.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.buddyup.api.accounts.internal.AccountSessions.ActiveSession;
import com.buddyup.api.accounts.internal.AccountSessions.Credential;

@Service
class AccountRecovery {
	private final JdbcTemplate jdbc;
	private final AccountSessions sessions;
	private final RegistrationSecrets secrets;
	private final PasswordEncoder passwords;
	private final Clock clock;
	private final RegistrationConfiguration.Settings codes;

	AccountRecovery(JdbcTemplate jdbc, AccountSessions sessions, RegistrationSecrets secrets, PasswordEncoder passwords,
			Clock clock, RegistrationConfiguration.Settings codes) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.secrets = secrets;
		this.passwords = passwords;
		this.clock = clock;
		this.codes = codes;
	}

	@Transactional
	void requestPasswordReset(String email) {
		var accounts = jdbc.query("""
				select id from buddyup.account where email_normalized = ? and email_verified_at is not null
				and password_hash is not null and state = 'ACTIVE' for update
				""", (row, index) -> row.getObject(1, UUID.class), email);
		if (accounts.isEmpty()) {
			return;
		}
		UUID accountId = accounts.getFirst();
		OffsetDateTime now = OffsetDateTime.now(clock);
		UUID id = UUID.randomUUID();
		String token = secrets.passwordResetToken(id);
		insertChallenge(id, accountId, null, email, "PASSWORD_RESET", secrets.proofDigest(token), now, now.plusMinutes(30));
		jdbc.update("update buddyup.auth_challenge set next_delivery_at = ? where id = ?", now, id);
	}

	@Transactional
	void resetPassword(String token, String newPassword) {
		String digest = secrets.proofDigest(token);
		UUID owner = jdbc.query("""
				select account_id from buddyup.auth_challenge
				where kind = 'PASSWORD_RESET' and secret_digest = ?
				""", (row, index) -> row.getObject(1, UUID.class), digest)
				.stream().findFirst().orElseThrow(RegistrationFailure::invalidProof);
		jdbc.query("select id from buddyup.account where id = ? for update", row -> { }, owner);
		OffsetDateTime now = OffsetDateTime.now(clock);
		int consumed = jdbc.update("""
				update buddyup.auth_challenge c set consumed_at = ? from buddyup.account a
				where c.account_id = a.id and c.account_id = ? and c.kind = 'PASSWORD_RESET' and c.secret_digest = ?
				  and c.consumed_at is null and c.expires_at > ? and c.email_normalized = a.email_normalized
				  and a.state = 'ACTIVE' and a.email_verified_at is not null and a.password_hash is not null
				""", now, owner, digest, now);
		if (consumed == 0) {
			throw RegistrationFailure.invalidProof();
		}
		jdbc.update("update buddyup.account set password_hash = ?, session_epoch = session_epoch + 1 where id = ?",
				passwords.encode(newPassword), owner);
		revokeChallenges(owner, now, "PASSWORD_RESET");
		revokeChallenges(owner, now, "EMAIL_CHANGE");
		revokeChallenges(owner, now, "REAUTH_CHANGE_EMAIL");
	}

	@Transactional
	String createReauthentication(Credential credential, String password) {
		var session = requireActiveAccount(credential);
		if (session.passwordHash() == null || !passwords.matches(password, session.passwordHash())) {
			throw RegistrationFailure.invalidProof();
		}
		String proof = secrets.newProof();
		OffsetDateTime now = OffsetDateTime.now(clock);
		insertChallenge(UUID.randomUUID(), session.accountId(), session.id(), session.email(), "REAUTH_CHANGE_EMAIL",
				secrets.proofDigest(proof), now, now.plusMinutes(5));
		return proof;
	}

	@Transactional
	Delivery requestEmailChange(Credential credential, String proof, String newEmail) {
		var session = requireActiveAccount(credential);
		OffsetDateTime now = OffsetDateTime.now(clock);
		int consumed = proof == null || !proof.matches("[A-Za-z0-9_-]{43}") ? 0 : jdbc.update("""
				update buddyup.auth_challenge set consumed_at = ?
				where kind = 'REAUTH_CHANGE_EMAIL' and account_id = ? and session_id = ? and email_normalized = ?
				  and secret_digest = ? and consumed_at is null and expires_at > ?
				""", now, session.accountId(), session.id(), session.email(), secrets.proofDigest(proof), now);
		if (consumed == 0) {
			throw RegistrationFailure.reauthenticationRequired();
		}
		if (Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists(select from buddyup.account where email_normalized = ?)", Boolean.class, newEmail))) {
			throw RegistrationFailure.conflict("ACCOUNT_DATA_CONFLICT");
		}
		revokeChallenges(session.accountId(), now, "EMAIL_CHANGE");
		UUID id = UUID.randomUUID();
		String code = secrets.newCode();
		insertChallenge(id, session.accountId(), session.id(), newEmail, "EMAIL_CHANGE", secrets.codeDigest(id, code),
				now, now.plus(codes.codeLifetime()));
		return new Delivery(id, newEmail, code);
	}

	@Transactional
	boolean confirmEmailChange(Credential credential, UUID id, String code) {
		var session = requireActiveAccount(credential);
		OffsetDateTime now = OffsetDateTime.now(clock);
		var challenge = jdbc.query("""
				select email_normalized, secret_digest, attempt_count, expires_at from buddyup.auth_challenge
				where id = ? and kind = 'EMAIL_CHANGE' and account_id = ? and session_id = ?
				  and consumed_at is null and expires_at > ? for update
				""", (row, index) -> new EmailChallenge(row.getString(1), row.getString(2), row.getInt(3),
				row.getObject(4, OffsetDateTime.class)), id, session.accountId(), session.id(), now)
				.stream().findFirst().orElseThrow(RegistrationFailure::invalidProof);
		if (challenge.attempts() >= codes.maxCodeAttempts()) {
			throw RegistrationFailure.limited(
					Math.max(1, java.time.Duration.between(now, challenge.expiresAt()).toSeconds() + 1));
		}
		if (!secrets.matchesCode(id, code, challenge.digest())) {
			jdbc.update("update buddyup.auth_challenge set attempt_count = attempt_count + 1 where id = ?", id);
			return false;
		}
		try {
			jdbc.update("update buddyup.account set email_normalized = ?, email_verified_at = ? where id = ?",
					challenge.email(), now, session.accountId());
		} catch (DuplicateKeyException exception) {
			throw RegistrationFailure.conflict("ACCOUNT_DATA_CONFLICT");
		}
		jdbc.update("update buddyup.auth_challenge set consumed_at = ? where id = ?", now, id);
		revokeChallenges(session.accountId(), now, "PASSWORD_RESET");
		revokeChallenges(session.accountId(), now, "REAUTH_CHANGE_EMAIL");
		jdbc.update("""
				insert into buddyup.email_change_notice (id, previous_email, new_email, next_attempt_at)
				values (?, ?, ?, ?)
				""", UUID.randomUUID(), session.email(), challenge.email(), now);
		return true;
	}

	void cancelDelivery(UUID id) {
		jdbc.update("update buddyup.auth_challenge set consumed_at = ? where id = ? and consumed_at is null",
				OffsetDateTime.now(clock), id);
	}

	private ActiveSession requireActiveAccount(Credential credential) {
		var session = sessions.lockActive(credential);
		if (!"ACTIVE".equals(session.stage()) || session.email() == null) {
			throw RegistrationFailure.reauthenticationRequired();
		}
		return session;
	}

	private void insertChallenge(UUID id, UUID account, UUID session, String email, String kind, String digest,
			OffsetDateTime now, OffsetDateTime expiration) {
		jdbc.update("""
				insert into buddyup.auth_challenge
				    (id, account_id, session_id, email_normalized, kind, secret_digest, created_at, expires_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)
				""", id, account, session, email, kind, digest, now, expiration);
	}

	private void revokeChallenges(UUID account, OffsetDateTime now, String kind) {
		jdbc.update("""
				update buddyup.auth_challenge set consumed_at = ?
				where account_id = ? and kind = ? and consumed_at is null
				""", now, account, kind);
	}

	record Delivery(UUID id, String email, String secret) {}

	private record EmailChallenge(String email, String digest, int attempts, OffsetDateTime expiresAt) {}
}
