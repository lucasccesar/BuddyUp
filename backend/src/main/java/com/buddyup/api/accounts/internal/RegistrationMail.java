package com.buddyup.api.accounts.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.buddyup.api.accounts.internal.RegistrationConfiguration.Settings;
import com.buddyup.api.accounts.internal.RegistrationService.IssuedChallenge;

@Component
class RegistrationMail {
	private final ObjectProvider<JavaMailSender> mail;
	private final Settings settings;

	RegistrationMail(ObjectProvider<JavaMailSender> mail, Settings settings) {
		this.mail = mail;
		this.settings = settings;
	}

	JavaMailSender sender() {
		JavaMailSender sender = mail.getIfAvailable();
		if (sender == null || settings.from() == null || settings.from().isBlank()) {
			throw RegistrationFailure.unavailable();
		}
		return sender;
	}

	void send(JavaMailSender sender, IssuedChallenge challenge) {
		var message = new SimpleMailMessage();
		message.setFrom(settings.from());
		message.setTo(challenge.email());
		message.setSubject("BuddyUp - Código de confirmação de e-mail");
		message.setText("Seu código para continuar o cadastro no BuddyUp é " + challenge.code()
				+ ". Use somente o código mais recente. Se você não solicitou este cadastro, ignore a mensagem.");
		try {
			sender.send(message);
		} catch (MailException exception) {
			throw RegistrationFailure.unavailable();
		}
	}
}
