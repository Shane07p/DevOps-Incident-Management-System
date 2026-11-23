package com.settle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Finish a Google or GitHub sign-up: name the new org, and confirm the name the team will see (D50). */
public record OAuthSignupRequest(
		@NotBlank(message = "Code is required") @Size(max = 100, message = "Code is at most 100 characters") String code,
		@NotBlank(message = "Org name is required") @Size(max = 100, message = "Org name is at most 100 characters") String orgName,
		@NotBlank(message = "Your name is required") @Size(max = 100, message = "Your name is at most 100 characters") String displayName) {
}
