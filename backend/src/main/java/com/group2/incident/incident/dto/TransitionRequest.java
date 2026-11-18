package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload to transition an incident to a new status (FR-06, US-10).
 *
 * @param status the requested next status (required)
 * @param expectedVersion the incident version viewed by the client (required for optimistic locking, NFR-11.2)
 * @param note why the status changed; required (the recovery note) when moving to RESOLVED, optional otherwise
 */
public record TransitionRequest(
		@NotNull(message = "Status is required")
		IncidentStatus status,

		@NotNull(message = "The incident version you saw is required")
		Long expectedVersion,

		@Size(max = 2000, message = "The note can have at most 2,000 characters")
		String note
) {
}
