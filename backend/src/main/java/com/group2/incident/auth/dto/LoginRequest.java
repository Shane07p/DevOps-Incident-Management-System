package com.group2.incident.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email")
		@Size(max = 254, message = "Email is at most 254 characters") String email,
		@NotBlank(message = "Password is required") @Size(max = 72, message = "Password is at most 72 characters") String password) {
}
