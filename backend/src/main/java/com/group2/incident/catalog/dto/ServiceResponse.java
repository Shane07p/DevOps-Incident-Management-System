package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public record ServiceResponse(
        Long id,
        String name,
        String description,
        OwnerDto owner,
        Criticality criticality,
        Map<Severity, SlaTargetDto> targets,
        boolean publicVisible,
        String publicName,
        // The value this service has in the org's telemetry (D49): the saved one, or the default.
        String telemetryLabel,
        Instant createdAt,
        Instant updatedAt
) {
    public static ServiceResponse from(ServiceEntity service) {
        Map<Severity, SlaTargetDto> targetMap = new EnumMap<>(Severity.class);
        if (service.getTargets() != null) {
            service.getTargets().forEach((severity, target) ->
                    targetMap.put(severity, new SlaTargetDto(target.getResponseMinutes(), target.getResolutionMinutes()))
            );
        }
        OwnerDto ownerDto = service.getOwner() != null
                ? new OwnerDto(service.getOwner().getId(), service.getOwner().getDisplayName())
                : null;
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getDescription(),
                ownerDto,
                service.getCriticality(),
                targetMap,
                service.isPublicVisible(),
                service.getPublicName(),
                service.telemetryLabel(),
                service.getCreatedAt(),
                service.getUpdatedAt()
        );
    }
}
