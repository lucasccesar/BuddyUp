package com.buddyup.api.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.buddyup.api.accounts.internal.RegistrationConfiguration.Settings;

@Component
class RegistrationSecrets {
	private final SecureRandom random = new SecureRandom();
	private final Settings settings;

	RegistrationSecrets(Settings settings) {
		this.settings = settings;
	}

	String newCode() {
		return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
	}

	String newProof() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	String codeDigest(UUID challengeId, String code) {
		return keyedDigest("email-code:" + challengeId + ":" + code);
	}

	String sourceDigest(String address) {
		return keyedDigest("source:" + address);
	}

	String passwordResetToken(UUID challengeId) {
		byte[] digest = HexFormat.of().parseHex(keyedDigest("password-reset:" + challengeId));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	boolean matchesCode(UUID challengeId, String code, String digest) {
		var hex = HexFormat.of();
		return MessageDigest.isEqual(hex.parseHex(digest), hex.parseHex(codeDigest(challengeId, code)));
	}

	String proofDigest(String proof) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(proof.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String keyedDigest(String value) {
		String key = settings.codeKey();
		if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw RegistrationFailure.unavailable();
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
