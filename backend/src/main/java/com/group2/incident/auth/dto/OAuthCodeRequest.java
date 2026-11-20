package com.group2.incident.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one-time code from the URL fragment after a Google or GitHub sign-in (D50). */
public record OAuthCodeRequest(
		@NotBlank(message = "Code is required") @Size(max = 100, message = "Code is at most 100 characters") String code) {
}
