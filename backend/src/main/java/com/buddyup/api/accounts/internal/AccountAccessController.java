package com.buddyup.api.accounts.internal;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.buddyup.api.accounts.internal.AccountSessions.Channel;
import com.buddyup.api.accounts.internal.AccountSessions.Credential;
import com.buddyup.api.accounts.internal.AccountSessions.SessionContext;
import com.buddyup.api.accounts.internal.RegistrationController.EmailChallengeResponse;
import com.buddyup.api.accounts.internal.RegistrationController.EmailConfirmation;
import com.buddyup.api.accounts.internal.RegistrationController.EmailRequest;

@RestController
class AccountAccessController {
	private static final String SESSION_COOKIE = "__Host-buddyup_session";
	private static final String CSRF_HEADER = "X-CSRF-Token";
	private static final Duration WEB_COOKIE_LIFETIME = Duration.ofDays(30);

	private final AccountSessions sessions;
	private final AccountRecovery recovery;
	private final AccountAttemptLimits limits;
	private final AccountMail mail;
	private final AccountSettings settings;

	AccountAccessController(AccountSessions sessions, AccountRecovery recovery, AccountAttemptLimits limits,
			AccountMail mail, AccountSettings settings) {
		this.sessions = sessions;
		this.recovery = recovery;
		this.limits = limits;
		this.mail = mail;
		this.settings = settings;
	}

	@PostMapping("/v1/auth/login")
	ResponseEntity<LoginResponse> loginWithPassword(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (body.client() == Channel.WEB ? !settings.trusts(origin) : origin != null) {
			throw RegistrationFailure.forbidden("ORIGIN_FORBIDDEN");
		}
		String email = AccountSessions.normalizeEmail(body.email());
		limits.check("login", request.getRemoteAddr(), email, 120, 20);
		boolean rememberMe = Boolean.TRUE.equals(body.rememberMe());
		var login = sessions.login(email, body.password(), body.client(), rememberMe);
		var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		boolean web = body.client() == Channel.WEB;
		if (web) {
			response.header(HttpHeaders.SET_COOKIE, cookie(login.token(), rememberMe ? WEB_COOKIE_LIFETIME : null));
		}
		return response.body(new LoginResponse(body.client(), login.session(), web ? null : login.token()));
	}

	@GetMapping("/v1/auth/session")
	ResponseEntity<SessionContext> getCurrentSession(HttpServletRequest request) {
		var credential = credential(request, false);
		var session = sessions.current(credential);
		var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		if (credential.channel() == Channel.WEB && session.rememberMe()) {
			response.header(HttpHeaders.SET_COOKIE, cookie(credential.token(), WEB_COOKIE_LIFETIME));
		}
		return response.body(session);
	}

	@DeleteMapping("/v1/auth/session")
	ResponseEntity<Void> logout(HttpServletRequest request) {
		var credential = credential(request, true);
		sessions.logout(credential);
		var response = ResponseEntity.noContent().cacheControl(CacheControl.noStore());
		if (credential.channel() == Channel.WEB) {
			response.header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
		}
		return response.build();
	}

	@PostMapping("/v1/auth/reauthentications")
	ResponseEntity<ReauthenticationResponse> createReauthentication(
			@Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
		var credential = credential(request, true);
		if (!"CHANGE_EMAIL".equals(body.purpose()) || body.password() == null || body.password().isBlank()
				|| body.providerProof() != null) {
			throw RegistrationFailure.reauthenticationRequired();
		}
		limits.check("reauthentication", request.getRemoteAddr(), credential.token(), 120, 20);
		String proof = recovery.createReauthentication(credential, body.password());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReauthenticationResponse(proof));
	}

	@PostMapping("/v1/auth/password-reset-requests")
	ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
		String email = AccountSessions.normalizeEmail(body.email());
		settings.requirePasswordResetUrl();
		limits.check("password-reset", request.getRemoteAddr(), email, 30, 5);
		recovery.requestPasswordReset(email);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
	}

	@PostMapping("/v1/auth/password-resets")
	ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordReset body) {
		recovery.resetPassword(body.resetToken(), body.password());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PostMapping("/v1/accounts/me/email-change-requests")
	ResponseEntity<EmailChallengeResponse> requestEmailChange(@Valid @RequestBody EmailRequest body,
			@RequestHeader(name = "X-Reauthentication-Token", required = false) String reauthentication,
			HttpServletRequest request) {
		var credential = credential(request, true);
		String email = AccountSessions.normalizeEmail(body.email());
		limits.check("email-change", request.getRemoteAddr(), email, 60, 10);
		var delivery = recovery.requestEmailChange(credential, reauthentication, email);
		try {
			mail.sendEmailChangeCode(delivery);
		} catch (RegistrationFailure failure) {
			recovery.cancelDelivery(delivery.id());
			throw failure;
		}
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(new EmailChallengeResponse(delivery.id()));
	}

	@PostMapping("/v1/accounts/me/email-change-confirmations")
	ResponseEntity<Void> confirmEmailChange(@Valid @RequestBody EmailConfirmation body, HttpServletRequest request) {
		if (!recovery.confirmEmailChange(credential(request, true), body.challengeId(), body.code())) {
			throw RegistrationFailure.invalidProof();
		}
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private Credential credential(HttpServletRequest request, boolean command) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (authorization != null) {
			if (origin != null) {
				throw RegistrationFailure.forbidden("ORIGIN_FORBIDDEN");
			}
			if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
				throw RegistrationFailure.sessionRequired();
			}
			return new Credential(authorization.substring(7), Channel.MOBILE);
		}
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (SESSION_COOKIE.equals(cookie.getName())) {
					if (command && !settings.trusts(origin)) {
						throw RegistrationFailure.forbidden("ORIGIN_FORBIDDEN");
					}
					if (command && !sessions.matchesCsrf(cookie.getValue(), request.getHeader(CSRF_HEADER))) {
						throw RegistrationFailure.forbidden("CSRF_INVALID");
					}
					return new Credential(cookie.getValue(), Channel.WEB);
				}
			}
		}
		throw RegistrationFailure.sessionRequired();
	}

	private String cookie(String token, Duration maxAge) {
		var cookie = ResponseCookie.from(SESSION_COOKIE, token)
				.httpOnly(true).secure(true).path("/").sameSite("Lax");
		if (maxAge != null) {
			cookie.maxAge(maxAge);
		}
		return cookie.build().toString();
	}

	record LoginRequest(@NotBlank @Size(max = 254) @jakarta.validation.constraints.Email String email,
			@NotBlank @Size(max = 128) String password, @NotNull Channel client, Boolean rememberMe) {}

	record LoginResponse(Channel client, SessionContext session,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
			String sessionToken) {}

	record ReauthenticationRequest(@NotBlank @Size(max = 32) String purpose,
			@Size(max = 128) String password, @Size(max = 4096) String providerProof) {}

	record ReauthenticationResponse(String reauthenticationToken) {}

	record PasswordReset(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String resetToken,
			@NotBlank @Size(min = 12, max = 128) String password) {}
}
