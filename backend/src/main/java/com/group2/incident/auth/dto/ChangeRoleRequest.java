package com.group2.incident.auth.dto;

import com.group2.incident.auth.entity.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
		@NotNull(message = "Role is required")
		Role role
) {
}
