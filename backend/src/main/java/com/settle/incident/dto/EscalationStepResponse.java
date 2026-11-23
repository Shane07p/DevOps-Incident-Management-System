package com.settle.incident.dto;

import com.settle.incident.entity.EscalationStep;

public record EscalationStepResponse(
		Long id,
		int stepNumber,
		Long responderId,
		String responderDisplayName,
		int waitMinutes
) {
	public static EscalationStepResponse from(EscalationStep step) {
		return new EscalationStepResponse(
				step.getId(),
				step.getStepNumber(),
				step.getResponder().getId(),
				step.getResponder().getDisplayName(),
				step.getWaitMinutes()
		);
	}
}
