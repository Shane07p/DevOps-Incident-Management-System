package com.group2.incident.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MonitorRequest(

    @NotBlank
    @Size(max = 100)
    String name,

    @NotBlank
    @Size(max = 2048)
    String url,

    @NotNull
    @Min(100)
    @Max(599)
    Integer expectedStatus,

    @NotNull
    @Min(1)
    @Max(86400)
    Integer intervalSeconds,

    @NotNull
    @Min(1)
    Long serviceId,

    @NotNull
    @Min(1)
    @Max(100)
    Integer failureThreshold,

    @NotNull
    Boolean enabled

) {}