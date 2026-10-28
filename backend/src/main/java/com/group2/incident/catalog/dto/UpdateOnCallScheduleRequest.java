package com.group2.incident.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpdateOnCallScheduleRequest(
        @NotNull Long fallbackContactId,
        List<@Valid ShiftRequest> shifts
) {
}
