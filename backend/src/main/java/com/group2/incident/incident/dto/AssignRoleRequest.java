package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentRole;
import jakarta.validation.constraints.NotNull;

/** Comms or Ops for one person. The Lead is the assignee, changed through {@code PUT /assignee} (D39). */
public record AssignRoleRequest(
		@NotNull(message = "Role is required")
		IncidentRole role,
		@NotNull(message = "User is required")
		Long userId
) {
}
