package com.settle.investigation.dto;

public record PostmortemDraftDto(
		String summary,
		String impact,
		String rootCause,
		String resolution,
		String wentWell,
		String wentBadly
) {
}
