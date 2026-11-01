package com.group2.incident.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "Request to replace the full set of dependencies for a service")
public record UpdateDependenciesRequest(
        @Schema(description = "List of service IDs that this service depends on", example = "[2, 3]")
        @NotNull(message = "dependsOn must not be null")
        List<Long> dependsOn
) {
    public UpdateDependenciesRequest {
        if (dependsOn == null) {
            dependsOn = List.of();
        }
    }
}
