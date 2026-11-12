package com.group2.incident.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePersonalAccessTokenRequest(
		@NotBlank(message = "Token name is required")
		@Size(max = 100, message = "Token name must not exceed 100 characters")
		String name
) {}

