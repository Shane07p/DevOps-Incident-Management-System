package com.group2.incident.incident.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record EscalationStepRequest(
		@NotNull(message = "stepNumber is required")
		@Min(value = 1, message = "stepNumber must be at least 1")
		Integer stepNumber,

		@NotNull(message = "responderId is required")
		Long responderId,

		@NotNull(message = "waitMinutes is required")
		@Min(value = 1, message = "waitMinutes must be at least 1")
		Integer waitMinutes
) {
}
