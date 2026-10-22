package com.group2.incident.catalog.dto;

import jakarta.validation.constraints.NotNull;

public record SlaTargetDto(
        Integer responseMinutes,
        @NotNull(message = "Resolution target is required")
        Integer resolutionMinutes
) {}
