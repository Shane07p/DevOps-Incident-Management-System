package com.group2.incident.catalog.controller;

import com.group2.incident.catalog.dto.CreateServiceRequest;
import com.group2.incident.catalog.dto.ServiceResponse;
import com.group2.incident.catalog.service.ServiceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
@Tag(name = "Services", description = "Service catalog: owner, criticality and SLA targets per severity")
public class ServiceController {

    private final ServiceCatalogService serviceCatalogService;

    public ServiceController(ServiceCatalogService serviceCatalogService) {
        this.serviceCatalogService = serviceCatalogService;
    }

    @GetMapping
    @Operation(summary = "List services, by name")
    public List<ServiceResponse> list() {
        return serviceCatalogService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one service")
    public ServiceResponse get(@PathVariable Long id) {
        return serviceCatalogService.get(id);
    }

    @PostMapping
    @Operation(summary = "Register a service (Admin)")
    public ResponseEntity<ServiceResponse> create(
            @Valid @RequestBody CreateServiceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
        ServiceResponse created = serviceCatalogService.create(request, actorId);
        return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a service's details and targets (Admin)")
    public ServiceResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreateServiceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
        return serviceCatalogService.update(id, request, actorId);
    }
}
