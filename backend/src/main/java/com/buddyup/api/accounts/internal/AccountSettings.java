package com.buddyup.api.accounts.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("buddyup.accounts")
record AccountSettings(String webOrigin, URI passwordResetUrl) {

	AccountSettings {
		if (passwordResetUrl != null) {
			String scheme = passwordResetUrl.getScheme();
			String host = passwordResetUrl.getHost();
			boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
			if (host == null || passwordResetUrl.getUserInfo() != null
					|| passwordResetUrl.getRawFragment() != null || passwordResetUrl.getRawQuery() != null
					|| (!"https".equals(scheme) && !("http".equals(scheme) && loopback))) {
				throw new IllegalArgumentException("Configure an absolute HTTPS password reset URL without query or fragment");
			}
		}
	}

	String resetLink(String token) {
		requirePasswordResetUrl();
		return passwordResetUrl.toASCIIString() + "#token=" + token;
	}

	void requirePasswordResetUrl() {
		if (passwordResetUrl == null) {
			throw RegistrationFailure.unavailable();
		}
	}

	boolean trusts(String origin) {
		return origin != null && origin.equals(webOrigin);
	}
}
