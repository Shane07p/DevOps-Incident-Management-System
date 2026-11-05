package com.group2.incident.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create or change an uptime monitor. The URL's host is checked too: see MonitorTargetGuard. */
public record MonitorRequest(
		@NotBlank(message = "Name is required")
		@Size(max = 100, message = "Name must be at most 100 characters")
		String name,

		@NotBlank(message = "URL is required")
		@Size(max = 2048, message = "URL must be at most 2048 characters")
		String url,

		@NotNull(message = "Expected status is required")
		@Min(value = 100, message = "Expected status must be between 100 and 599")
		@Max(value = 599, message = "Expected status must be between 100 and 599")
		Integer expectedStatus,

		// At least 30 seconds, so one monitor cannot hit a site every second.
		@NotNull(message = "Interval is required")
		@Min(value = 30, message = "Interval must be at least 30 seconds")
		@Max(value = 86400, message = "Interval must be at most 86400 seconds (one day)")
		Integer intervalSeconds,

		@NotNull(message = "Service is required")
		Long serviceId,

		@NotNull(message = "Failure threshold is required")
		@Min(value = 1, message = "Failure threshold must be between 1 and 100")
		@Max(value = 100, message = "Failure threshold must be between 1 and 100")
		Integer failureThreshold,

		@NotNull(message = "Enabled is required")
		Boolean enabled
) {
}
