package com.settle.investigation.dto;

import com.settle.investigation.entity.ActionDecision;
import jakarta.validation.constraints.NotNull;

public record ActionDecisionRequest(
		@NotNull(message = "decision is required")
		ActionDecision decision
) {
}
