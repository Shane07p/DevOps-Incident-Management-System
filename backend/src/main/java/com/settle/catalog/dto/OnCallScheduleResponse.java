package com.settle.catalog.dto;

import com.settle.catalog.entity.OnCallShift;
import com.settle.catalog.entity.ServiceEntity;
import java.util.List;

public record OnCallScheduleResponse(
        Long serviceId,
        Long fallbackContactId,
        String fallbackContactName,
        List<ShiftResponse> shifts
) {
    public static OnCallScheduleResponse from(ServiceEntity service, List<OnCallShift> shifts) {
        Long fallbackId = service.getFallbackContact() != null ? service.getFallbackContact().getId() : null;
        String fallbackName = service.getFallbackContact() != null ? service.getFallbackContact().getDisplayName() : null;
        List<ShiftResponse> shiftResponses = shifts != null
                ? shifts.stream().map(ShiftResponse::from).toList()
                : List.of();
        return new OnCallScheduleResponse(service.getId(), fallbackId, fallbackName, shiftResponses);
    }
}
