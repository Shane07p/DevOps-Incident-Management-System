package com.group2.incident.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "Replaces the full set of services a service depends on")
public record UpdateDependenciesRequest(
		// Required, so a body of {} or a misspelt field is refused rather than read as "no dependencies".
		@Schema(description = "Ids of the services this service depends on; [] removes them all", example = "[2, 3]")
		@NotNull(message = "dependsOn is required; send [] to remove every dependency")
		List<@NotNull(message = "dependsOn must not contain null") Long> dependsOn
) {
}
