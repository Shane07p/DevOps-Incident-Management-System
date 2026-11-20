package com.group2.incident.investigation.dto;

import com.group2.incident.investigation.entity.ActionDecision;
import jakarta.validation.constraints.NotNull;

public record ActionDecisionRequest(
		@NotNull(message = "decision is required")
		ActionDecision decision
) {
}
