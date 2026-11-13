package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to transition an incident to a new status (FR-06, US-10).
 *
 * @param status the requested next status (required)
 * @param expectedVersion the incident version viewed by the client (required for optimistic locking, NFR-11.2)
 * @param recoveryNote description of the fix/recovery, mandatory when transitioning to RESOLVED
 */
public record TransitionRequest(
		@NotNull(message = "Status is required")
		IncidentStatus status,

		@NotNull(message = "The incident version you saw is required")
		Long expectedVersion,

		String recoveryNote
) {
}
