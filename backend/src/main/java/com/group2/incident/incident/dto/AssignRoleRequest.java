package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentRole;
import jakarta.validation.constraints.NotNull;

/**
 * Comms or Ops for one person. The Lead is the assignee, changed through {@code PUT /assignee} (D39).
 * {@code expectedVersion} is the incident's {@code version} as the screen showed it (NFR-11.2).
 */
public record AssignRoleRequest(
		@NotNull(message = "Role is required")
		IncidentRole role,
		@NotNull(message = "User is required")
		Long userId,
		@NotNull(message = "The incident version you saw is required")
		Long expectedVersion
) {
}
