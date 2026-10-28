package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
		@NotBlank(message = "Title is required")
		@Size(max = 200, message = "Title must not exceed 200 characters")
		String title,

		@NotBlank(message = "Description is required")
		String description,

		@NotNull(message = "Service is required")
		Long serviceId,

		@NotNull(message = "Severity is required")
		Severity severity
) {
}
