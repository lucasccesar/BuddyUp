package com.buddyup.api.accounts.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationConfiguration.Settings.class)
class RegistrationConfiguration {

	@Bean
	Clock registrationClock() {
		return Clock.systemUTC();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		String encoding = "pbkdf2@SpringSecurity_v5_8";
		return new DelegatingPasswordEncoder(encoding,
				Map.of(encoding, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
	}

	@ConfigurationProperties("buddyup.registration")
	record Settings(String codeKey, String from,
			@DefaultValue("10m") Duration codeLifetime,
			@DefaultValue("24h") Duration proofLifetime,
			@DefaultValue("60s") Duration resendDelay,
			@DefaultValue("5") int maxCodeAttempts,
			@DefaultValue("5") int maxRequestsPerEmail,
			@DefaultValue("30") int maxRequestsPerSource) {
		Settings {
			if (codeLifetime.isNegative() || codeLifetime.isZero() || proofLifetime.isNegative()
					|| proofLifetime.isZero() || resendDelay.isNegative() || resendDelay.isZero()
					|| maxCodeAttempts < 1 || maxRequestsPerEmail < 1 || maxRequestsPerSource < 1) {
				throw new IllegalArgumentException("Registration lifetimes and limits must be positive");
			}
		}
	}
}
