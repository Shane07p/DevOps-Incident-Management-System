package com.group2.incident.catalog.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ShiftRequest(
        @NotNull Long responderId,
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {
}
