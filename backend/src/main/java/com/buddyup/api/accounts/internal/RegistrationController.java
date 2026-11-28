package com.buddyup.api.accounts.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth/registration")
class RegistrationController {
	private final RegistrationService registration;
	private final RegistrationMail mail;

	RegistrationController(RegistrationService registration, RegistrationMail mail) {
		this.registration = registration;
		this.mail = mail;
	}

	@PostMapping("/email-challenges")
	ResponseEntity<EmailChallengeResponse> startEmailRegistration(@Valid @RequestBody EmailRequest body,
			HttpServletRequest request) {
		var sender = mail.sender();
		var challenge = registration.start(body.email(), request.getRemoteAddr());
		if (challenge.code() != null) {
			try {
				mail.send(sender, challenge);
			} catch (RegistrationFailure failure) {
				registration.cancelDelivery(challenge.id());
				throw failure;
			}
		}
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(new EmailChallengeResponse(challenge.id()));
	}

	@PostMapping("/email-confirmations")
	ResponseEntity<RegistrationProofResponse> confirmRegistrationEmail(@Valid @RequestBody EmailConfirmation body) {
		String token = registration.confirmEmail(body.challengeId(), body.code());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RegistrationProofResponse(token));
	}

	@PostMapping
	ResponseEntity<Void> completeEmailRegistration(@Valid @RequestBody EmailRegistration body) {
		registration.completeRegistration(body.registrationToken(), body.password(), body.profile());
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

	record EmailRequest(@NotBlank @Size(max = 254) @Email
			@Pattern(regexp = "[^\\s@,;<>]+@[^\\s@,;<>]+") String email) {}

	record EmailChallengeResponse(UUID challengeId) {}

	record EmailConfirmation(@NotNull UUID challengeId,
			@NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

	record RegistrationProofResponse(String registrationToken) {}

	record EmailRegistration(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String registrationToken,
			@NotBlank @Size(min = 12, max = 128) String password, @Valid @NotNull RegistrationProfile profile) {}
}
