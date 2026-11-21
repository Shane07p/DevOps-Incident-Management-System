package com.group2.incident.catalog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateServiceStatusPageRequest(
        @NotNull(message = "Visibility is required") Boolean publicVisible,
        @Size(max = 100, message = "Public name must be at most 100 characters") String publicName,
        @NotNull(message = "Version is required")
        @PositiveOrZero(message = "Version must not be negative") Long version
) {}
