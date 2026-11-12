package com.group2.incident.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A directed edge representing a dependency (from dependent service to dependency service)")
public record ServiceMapEdgeDto(
        @Schema(description = "Dependent service ID", example = "1")
        Long from,
        @Schema(description = "Service ID depended upon", example = "2")
        Long to
) {}
