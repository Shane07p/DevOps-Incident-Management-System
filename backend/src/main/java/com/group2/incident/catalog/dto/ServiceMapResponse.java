package com.group2.incident.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Service map containing dependency graph nodes and edges")
public record ServiceMapResponse(
        @Schema(description = "List of service nodes in the system")
        List<ServiceMapNodeDto> nodes,
        @Schema(description = "List of dependency edges between services")
        List<ServiceMapEdgeDto> edges
) {}
