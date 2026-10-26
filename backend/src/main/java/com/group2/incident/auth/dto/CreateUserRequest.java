package com.group2.incident.auth.dto;

import com.group2.incident.auth.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
		@NotBlank(message = "Username is required")
		@Size(max = 50, message = "Username must not exceed 50 characters")
		String username,

		@NotBlank(message = "Display name is required")
		@Size(max = 100, message = "Display name must not exceed 100 characters")
		String displayName,

		@NotBlank(message = "Password is required")
		@Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
		String password,

		@NotNull(message = "Role is required")
		Role role
) {
}
