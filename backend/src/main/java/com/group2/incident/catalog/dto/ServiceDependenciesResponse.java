package com.group2.incident.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Service dependencies response")
public record ServiceDependenciesResponse(
        @Schema(description = "Service ID", example = "1")
        Long serviceId,
        @Schema(description = "IDs of services this service depends on", example = "[2, 3]")
        List<Long> dependsOn
) {}
