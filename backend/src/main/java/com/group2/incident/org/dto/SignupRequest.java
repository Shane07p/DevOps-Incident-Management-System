package com.group2.incident.org.dto;

import com.group2.incident.common.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email")
		@Size(max = 254, message = "Email is at most 254 characters") String email,
		@NotBlank(message = "Password is required")
		@Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
		@MaxBytes(value = 72, message = "Password must be at most 72 bytes; letters such as é count as two") String password,
		@NotBlank(message = "Your name is required") @Size(max = 100, message = "Your name is at most 100 characters") String displayName,
		@NotBlank(message = "Org name is required") @Size(max = 100, message = "Org name is at most 100 characters") String orgName) {
}
