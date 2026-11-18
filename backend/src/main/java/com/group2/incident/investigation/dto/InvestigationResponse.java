package com.group2.incident.investigation.dto;

import java.time.Instant;
import java.util.List;

public record InvestigationResponse(
		Long id,
		String summary,
		List<InvestigationHypothesisDto> hypotheses,
		List<SuggestedActionDto> suggestedActions,
		Instant createdAt
) {
}
