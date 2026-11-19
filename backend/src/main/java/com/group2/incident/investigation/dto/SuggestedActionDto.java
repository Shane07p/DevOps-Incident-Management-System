package com.group2.incident.investigation.dto;

import com.group2.incident.investigation.entity.ActionDecision;
import java.time.Instant;

public record SuggestedActionDto(
		Long id,
		String text,
		ActionDecision decision,
		String decidedBy,
		Instant decidedAt
) {
}
