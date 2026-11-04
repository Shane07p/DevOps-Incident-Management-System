package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentRole;
import jakarta.validation.constraints.NotNull;

public record AssignRoleRequest(
		@NotNull(message = "Role is required")
		IncidentRole role,
		Long userId,
		Long assigneeId
) {
	public Long targetUserId() {
		return userId != null ? userId : assigneeId;
	}
}
