package com.buddyup.api.accounts.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.buddyup.api.accounts.internal.AccountRecovery.Delivery;

@Component
class AccountMail {
	private static final Logger LOG = LoggerFactory.getLogger(AccountMail.class);

	private final RegistrationMail mail;
	private final RegistrationSecrets secrets;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final AccountSettings settings;

	AccountMail(RegistrationMail mail, RegistrationSecrets secrets, JdbcTemplate jdbc, Clock clock,
			AccountSettings settings) {
		this.mail = mail;
		this.secrets = secrets;
		this.jdbc = jdbc;
		this.clock = clock;
		this.settings = settings;
	}

	void sendEmailChangeCode(Delivery delivery) {
		mail.sendMessage(mail.sender(), delivery.email(), "BuddyUp - Confirmação de novo e-mail",
				"Seu código para confirmar o novo e-mail no BuddyUp é " + delivery.secret() + ".");
	}

	@Scheduled(fixedDelayString = "${buddyup.accounts.mail-poll-ms:1000}")
	void deliverPendingMail() {
		OffsetDateTime now = OffsetDateTime.now(clock);
		for (var delivery : claimPasswordResets(now)) {
			sendPasswordReset(delivery);
		}
		for (var notice : claimEmailChangeNotices(now)) {
			sendEmailChangeNotice(notice);
		}
	}

	private java.util.List<PendingReset> claimPasswordResets(OffsetDateTime now) {
		return jdbc.query("""
				update buddyup.auth_challenge c set next_delivery_at = ?
				where id in (
				    select id from buddyup.auth_challenge
				    where kind = 'PASSWORD_RESET' and next_delivery_at <= ?
				      and consumed_at is null and expires_at > ?
				    order by next_delivery_at, id limit 10 for update skip locked
				)
				returning c.id, c.email_normalized
				""", (row, index) -> new PendingReset(row.getObject(1, UUID.class), row.getString(2)),
				now.plusMinutes(5), now, now);
	}

	private java.util.List<PendingNotice> claimEmailChangeNotices(OffsetDateTime now) {
		return jdbc.query("""
				update buddyup.email_change_notice n set next_attempt_at = ?
				where id in (
				    select id from buddyup.email_change_notice where next_attempt_at <= ?
				    order by next_attempt_at, id limit 10 for update skip locked
				)
				returning n.id, n.previous_email, n.new_email
				""", (row, index) -> new PendingNotice(row.getObject(1, UUID.class), row.getString(2),
				row.getString(3)), now.plusMinutes(5), now);
	}

	private void sendPasswordReset(PendingReset delivery) {
		try {
			String link = settings.resetLink(secrets.passwordResetToken(delivery.id()));
			mail.sendMessage(mail.sender(), delivery.email(), "BuddyUp - Redefinição de senha",
					"Abra este link para criar uma nova senha no BuddyUp:\n" + link
					+ "\nO link vale por 30 minutos e só pode ser usado uma vez. Se você não solicitou, ignore este e-mail.");
			jdbc.update("update buddyup.auth_challenge set next_delivery_at = null where id = ?", delivery.id());
		} catch (RegistrationFailure failure) {
			LOG.warn("Password reset email delivery failed. A retry is scheduled.");
		}
	}

	private void sendEmailChangeNotice(PendingNotice notice) {
		try {
			mail.sendMessage(mail.sender(), notice.previousEmail(), "BuddyUp - E-mail da conta alterado",
					"O e-mail da sua conta BuddyUp foi alterado para " + notice.newEmail()
					+ ". Se você não reconhece a mudança, entre em contato com o suporte do BuddyUp.");
			jdbc.update("delete from buddyup.email_change_notice where id = ?", notice.id());
		} catch (RegistrationFailure failure) {
			LOG.warn("Email change notice delivery failed. A retry is scheduled.");
		}
	}

	private record PendingReset(UUID id, String email) {}
	private record PendingNotice(UUID id, String previousEmail, String newEmail) {}
}
