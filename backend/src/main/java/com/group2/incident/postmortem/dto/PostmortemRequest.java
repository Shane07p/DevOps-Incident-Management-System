package com.group2.incident.postmortem.dto;

import jakarta.validation.constraints.Size;

public record PostmortemRequest(
		@Size(max = 10000) String summary,
		@Size(max = 10000) String impact,
		@Size(max = 10000) String rootCause,
		@Size(max = 10000) String resolution,
		@Size(max = 10000) String wentWell,
		@Size(max = 10000) String wentBadly,
		Long expectedVersion
) {
}
