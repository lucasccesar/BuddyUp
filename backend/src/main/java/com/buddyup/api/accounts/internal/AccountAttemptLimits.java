package com.buddyup.api.accounts.internal;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AccountAttemptLimits {
	private final JdbcTemplate jdbc;
	private final RegistrationSecrets secrets;
	private final Clock clock;

	AccountAttemptLimits(JdbcTemplate jdbc, RegistrationSecrets secrets, Clock clock) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.clock = clock;
	}

	void check(String operation, String remoteAddress, String subject, int sourceLimit, int subjectLimit) {
		take(operation + ":source", remoteAddress, sourceLimit);
		take(operation + ":subject", subject, subjectLimit);
	}

	private void take(String scope, String subject, int limit) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		var results = jdbc.query("""
				insert into buddyup.account_attempt_window as counter
				    (scope, subject_digest, window_started_at, attempts)
				values (?, ?, ?, 1)
				on conflict (scope, subject_digest) do update set
				    attempts = case when counter.window_started_at <= ? then 1 else counter.attempts + 1 end,
				    window_started_at = case when counter.window_started_at <= ? then excluded.window_started_at
				        else counter.window_started_at end
				where counter.window_started_at <= ? or counter.attempts < ?
				returning attempts
				""", (row, index) -> row.getInt(1), scope, secrets.sourceDigest(subject), now,
				now.minusHours(1), now.minusHours(1), now.minusHours(1), limit);
		if (results.isEmpty()) {
			OffsetDateTime start = jdbc.queryForObject("""
					select window_started_at from buddyup.account_attempt_window where scope = ? and subject_digest = ?
					""", OffsetDateTime.class, scope, secrets.sourceDigest(subject));
			throw RegistrationFailure.limited(Math.max(1, java.time.Duration.between(now, start.plusHours(1)).toSeconds() + 1));
		}
	}
}
