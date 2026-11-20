package com.group2.incident.investigation.dto;

import java.time.Instant;

public record DraftResponse(
		DraftKind kind,
		String text,
		PostmortemDraftDto postmortem,
		Instant generatedAt
) {
}
