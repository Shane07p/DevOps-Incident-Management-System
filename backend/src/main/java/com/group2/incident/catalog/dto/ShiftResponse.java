package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.OnCallShift;
import java.time.Instant;

public record ShiftResponse(
        Long id,
        Long responderId,
        String responderName,
        Instant startTime,
        Instant endTime
) {
    public static ShiftResponse from(OnCallShift shift) {
        return new ShiftResponse(
                shift.getId(),
                shift.getResponder().getId(),
                shift.getResponder().getDisplayName(),
                shift.getStartTime(),
                shift.getEndTime()
        );
    }
}
