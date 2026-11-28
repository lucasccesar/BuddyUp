package com.buddyup.api.accounts.internal;

import org.springframework.http.HttpStatus;

final class RegistrationFailure extends RuntimeException {
	final HttpStatus status;
	final String code;
	final long retryAfter;

	private RegistrationFailure(HttpStatus status, String code, long retryAfter) {
		super(code);
		this.status = status;
		this.code = code;
		this.retryAfter = retryAfter;
	}

	static RegistrationFailure invalidProof() {
		return new RegistrationFailure(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", 0);
	}

	static RegistrationFailure limited(long seconds) {
		return new RegistrationFailure(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", seconds);
	}

	static RegistrationFailure conflict(String code) {
		return new RegistrationFailure(HttpStatus.CONFLICT, code, 0);
	}

	static RegistrationFailure unavailable() {
		return new RegistrationFailure(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_UNAVAILABLE", 0);
	}

	static RegistrationFailure accountAccessUnavailable() {
		return new RegistrationFailure(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_ACCESS_UNAVAILABLE", 0);
	}

	static RegistrationFailure sessionRequired() {
		return new RegistrationFailure(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", 0);
	}

	static RegistrationFailure sessionRevoked() {
		return new RegistrationFailure(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", 0);
	}

	static RegistrationFailure forbidden(String code) {
		return new RegistrationFailure(HttpStatus.FORBIDDEN, code, 0);
	}

	static RegistrationFailure reauthenticationRequired() {
		return forbidden("REAUTHENTICATION_REQUIRED");
	}
}
