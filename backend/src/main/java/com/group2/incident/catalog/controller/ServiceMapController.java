package com.group2.incident.catalog.controller;

import com.group2.incident.catalog.dto.ServiceMapResponse;
import com.group2.incident.catalog.service.ServiceMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/service-map")
@Tag(name = "Service Map", description = "Service dependency graph and health status")
public class ServiceMapController {

    private final ServiceMapService serviceMapService;

    @GetMapping
    @Operation(summary = "Get the service map: every service with its health, and every dependency")
    public ServiceMapResponse getServiceMap() {
        return serviceMapService.getServiceMap();
    }
}
