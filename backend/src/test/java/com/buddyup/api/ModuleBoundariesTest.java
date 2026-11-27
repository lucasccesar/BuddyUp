package com.buddyup.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleBoundariesTest {

	@Test
	@DisplayName("[verify] Should Enforce Module Boundaries - When Application Modules Are Scanned")
	void shouldEnforceModuleBoundariesWhenApplicationModulesAreScanned() {
		var modules = ApplicationModules.of(BuddyUpApplication.class);

		assertThat(modules).extracting(module -> module.getIdentifier().toString())
				.contains("accounts", "catalog", "matchmaking", "chat", "social", "notifications",
						"recognition", "moderation", "metrics");
		modules.verify();
	}
}
