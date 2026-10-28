package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.OnCallShift;
import com.group2.incident.catalog.entity.ServiceEntity;
import java.util.List;

public record OnCallScheduleResponse(
        Long serviceId,
        Long fallbackContactId,
        String fallbackContactUsername,
        List<ShiftResponse> shifts
) {
    public static OnCallScheduleResponse from(ServiceEntity service, List<OnCallShift> shifts) {
        Long fallbackId = service.getFallbackContact() != null ? service.getFallbackContact().getId() : null;
        String fallbackUsername = service.getFallbackContact() != null ? service.getFallbackContact().getUsername() : null;
        List<ShiftResponse> shiftResponses = shifts != null
                ? shifts.stream().map(ShiftResponse::from).toList()
                : List.of();
        return new OnCallScheduleResponse(service.getId(), fallbackId, fallbackUsername, shiftResponses);
    }
}
