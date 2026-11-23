package com.settle.investigation.dto;

import java.time.Instant;

public record DraftResponse(
		DraftKind kind,
		String text,
		PostmortemDraftDto postmortem,
		Instant generatedAt
) {
}
