package com.group2.incident.heartbeat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HeartbeatRequest(
		@NotBlank(message = "Name is required")
		@Size(max = 100, message = "Name must be at most 100 characters")
		String name,

		@NotNull(message = "Service is required")
		Long serviceId,

		@NotNull(message = "Interval is required")
		@Min(value = 30, message = "Interval must be at least 30 seconds")
		@Max(value = 2592000, message = "Interval must be at most 2592000 seconds (30 days)")
		Integer intervalSeconds,

		@NotNull(message = "Grace period is required")
		@Min(value = 0, message = "Grace period must be at least 0 seconds")
		@Max(value = 86400, message = "Grace period must be at most 86400 seconds (one day)")
		Integer graceSeconds,

		@NotNull(message = "Enabled is required")
		Boolean enabled
) {
}
