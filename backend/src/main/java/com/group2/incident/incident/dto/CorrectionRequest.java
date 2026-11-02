package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CorrectionRequest(
		@NotBlank(message = "Correction details are required")
		@Size(max = 5000, message = "Correction details must not exceed 5000 characters")
		String details
) {
}
