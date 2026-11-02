package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotBlank;

public record CorrectionRequest(
		@NotBlank(message = "Correction details are required")
		String details
) {
}
