package com.group2.incident.auth.dto;

import jakarta.validation.constraints.NotNull;

/** {@code false} disables a member in the Admin's org, so they can no longer sign in to it; {@code true} enables them again. */
public record ChangeEnabledRequest(
		@NotNull(message = "Enabled is required")
		Boolean enabled
) {
}
