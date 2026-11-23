package com.settle.catalog.controller;

import com.settle.catalog.dto.CreateServiceRequest;
import com.settle.catalog.dto.ServiceDependenciesResponse;
import com.settle.catalog.dto.ServiceResponse;
import com.settle.catalog.dto.UpdateDependenciesRequest;
import com.settle.catalog.dto.UpdateServiceStatusPageRequest;
import com.settle.catalog.service.ServiceCatalogService;
import com.settle.catalog.service.ServiceMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/services")
@Tag(name = "Services", description = "Service catalog: owner, criticality and SLA targets per severity")
public class ServiceController {

    private final ServiceCatalogService serviceCatalogService;
    private final ServiceMapService serviceMapService;

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
    @Operation(summary = "Change a service's details and targets (Admin)",
            description = "Requires the loaded version; a stale version returns 409 without changing the service.")
    public ServiceResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreateServiceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
        return serviceCatalogService.update(id, request, actorId);
    }

    @PatchMapping("/{id}/status-page")
    @Operation(summary = "Change only a service's public status-page settings (Admin)")
    public ServiceResponse updateStatusPage(
            @PathVariable Long id,
            @Valid @RequestBody UpdateServiceStatusPageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
        return serviceCatalogService.updateStatusPage(id, request, actorId);
    }

    @PutMapping("/{id}/dependencies")
    @Operation(summary = "Replace the services this service depends on (Admin)")
    public ServiceDependenciesResponse replaceDependencies(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDependenciesRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
        return serviceMapService.replaceDependencies(id, request, actorId);
    }

    @GetMapping("/{id}/blast-radius")
    @Operation(summary = "What breaks if this service fails: every service that depends on it, directly or through others")
    public List<ServiceResponse> getBlastRadius(@PathVariable Long id) {
        return serviceMapService.getBlastRadius(id);
    }
}
