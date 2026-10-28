package com.group2.incident.incident.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpdateEscalationPolicyRequest(
		@NotNull(message = "steps list is required")
		@NotEmpty(message = "steps list must not be empty")
		@Valid
		List<EscalationStepRequest> steps
) {
}
