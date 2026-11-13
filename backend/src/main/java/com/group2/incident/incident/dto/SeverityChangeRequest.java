package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A severity change with its reason (FR-04.2). The SLA targets copied at creation stay as they were. */
public record SeverityChangeRequest(
		@NotNull(message = "Severity is required")
		Severity severity,

		@NotBlank(message = "A reason is required to change the severity")
		@Size(max = 500, message = "The reason can have at most 500 characters")
		String reason,

		@NotNull(message = "The incident version you saw is required")
		Long expectedVersion
) {
}
