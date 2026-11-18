package com.group2.incident.postmortem.dto;

import jakarta.validation.constraints.NotNull;

public record ReviewPostmortemRequest(
		@NotNull(message = "The postmortem version you saw is required") Long expectedVersion
) {
}
