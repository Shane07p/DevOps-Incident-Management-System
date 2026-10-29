package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.EscalationPolicy;
import java.time.Instant;
import java.util.List;

public record EscalationPolicyResponse(
		Long id,
		Long serviceId,
		List<EscalationStepResponse> steps,
		Instant updatedAt
) {
	public static EscalationPolicyResponse from(EscalationPolicy policy) {
		List<EscalationStepResponse> stepResponses = policy.getSteps()
				.stream()
				.map(EscalationStepResponse::from)
				.toList();
		return new EscalationPolicyResponse(
				policy.getId(),
				policy.getService().getId(),
				stepResponses,
				policy.getUpdatedAt()
		);
	}
}
