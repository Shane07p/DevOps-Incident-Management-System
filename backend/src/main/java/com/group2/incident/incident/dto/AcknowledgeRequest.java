package com.group2.incident.incident.dto;

import jakarta.validation.constraints.NotNull;

/** {@code expectedVersion} is the incident's {@code version} as the screen showed it (NFR-11.2). */
public record AcknowledgeRequest(
		@NotNull(message = "The incident version you saw is required")
		Long expectedVersion
) {
}
