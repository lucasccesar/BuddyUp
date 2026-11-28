package com.buddyup.api.accounts.internal;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record RegistrationProfile(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 40) String nickname,
		@NotBlank @Size(max = 64) String region,
		@NotNull @Size(min = 1, max = 20) List<@NotNull @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
				@Size(max = 35) String> languages,
		@NotNull @AssertTrue Boolean confirmsAdultAge) {}
