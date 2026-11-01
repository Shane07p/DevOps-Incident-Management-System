package com.group2.incident.catalog.controller;

import com.group2.incident.catalog.dto.ServiceMapResponse;
import com.group2.incident.catalog.service.ServiceCatalogService;
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

    private final ServiceCatalogService serviceCatalogService;

    @GetMapping
    @Operation(summary = "Get the complete service dependency map (Admin)")
    public ServiceMapResponse getServiceMap() {
        return serviceCatalogService.getServiceMap();
    }
}
