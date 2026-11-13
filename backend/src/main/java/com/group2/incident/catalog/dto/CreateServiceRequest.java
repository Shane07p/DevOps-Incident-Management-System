package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateServiceRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @NotNull(message = "Owner is required")
        Long ownerId,

        @NotNull(message = "Criticality is required")
        Criticality criticality,

        @NotNull(message = "Targets are required")
        Map<Severity, @Valid SlaTargetDto> targets,

        // Optional (D45). Left out on create: hidden; left out on update: unchanged.
        Boolean publicVisible,

        @Size(max = 100, message = "Public name must be at most 100 characters")
        String publicName,

        // Optional (D49): the value of the org's service label for this service in its own telemetry.
        // Left out on create: the default; left out on update: unchanged; blank: back to the default.
        @Size(max = 100, message = "Telemetry label must be at most 100 characters")
        String telemetryLabel
) {}
