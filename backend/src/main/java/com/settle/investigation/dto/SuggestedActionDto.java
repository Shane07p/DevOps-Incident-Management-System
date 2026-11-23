package com.settle.investigation.dto;

import com.settle.investigation.entity.ActionDecision;
import java.time.Instant;

public record SuggestedActionDto(
		Long id,
		String text,
		ActionDecision decision,
		String decidedBy,
		Instant decidedAt
) {
}
