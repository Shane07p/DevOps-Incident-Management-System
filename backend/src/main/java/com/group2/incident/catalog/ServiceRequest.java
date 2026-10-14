package com.group2.incident.catalog;

import com.group2.incident.incident.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Body of POST and PUT /api/services. Target rules are checked in {@link SlaTargetRules}. */
public record ServiceRequest(
		@NotBlank(message = "Name is required")
		@Size(max = 100, message = "Name must be at most 100 characters")
		String name,

		@Size(max = 1000, message = "Description must be at most 1000 characters")
		String description,

		@NotNull(message = "Owner is required")
		Long ownerId,

		@NotNull(message = "Criticality is required")
		Criticality criticality,

		@NotNull(message = "Targets are required")
		Map<Severity, SlaTargetDto> targets) {
}
