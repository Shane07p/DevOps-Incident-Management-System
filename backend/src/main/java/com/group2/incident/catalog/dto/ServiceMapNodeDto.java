package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.monitor.service.ServiceHealth;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A node in the service map")
public record ServiceMapNodeDto(
		@Schema(description = "Service ID", example = "1")
		Long id,
		@Schema(description = "Service name", example = "Payments")
		String name,
		@Schema(description = "Service criticality", example = "HIGH")
		Criticality criticality,
		@Schema(description = "Health from the service's monitors", example = "OPERATIONAL")
		ServiceHealth health
) {
}
