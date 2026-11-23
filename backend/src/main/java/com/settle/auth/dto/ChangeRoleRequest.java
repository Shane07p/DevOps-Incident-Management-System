package com.settle.auth.dto;

import com.settle.auth.entity.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
		@NotNull(message = "Role is required")
		Role role
) {
}
