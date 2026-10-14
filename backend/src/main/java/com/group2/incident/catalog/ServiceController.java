package com.group2.incident.catalog;

import com.group2.incident.auth.CurrentUser;
import com.group2.incident.incident.Severity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
class ServiceController {

	private final ServiceCatalog catalog;

	private final CurrentUser currentUser;

	ServiceController(ServiceCatalog catalog, CurrentUser currentUser) {
		this.catalog = catalog;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(summary = "List services, by name")
	List<ServiceResponse> list() {
		return catalog.list();
	}

	@GetMapping("/default-targets")
	@Operation(summary = "Default response target per severity, for prefilling a new service")
	Map<Severity, SlaTargetDto> defaultTargets() {
		return catalog.defaultTargets();
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one service")
	ServiceResponse get(@PathVariable Long id) {
		return catalog.get(id);
	}

	@PostMapping
	@Operation(summary = "Register a service (Admin)")
	ResponseEntity<ServiceResponse> create(@Valid @RequestBody ServiceRequest request) {
		ServiceResponse created = catalog.create(request, currentUser.require());
		return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Change a service's details and targets (Admin)")
	ServiceResponse update(@PathVariable Long id, @Valid @RequestBody ServiceRequest request) {
		return catalog.update(id, request, currentUser.require());
	}

}
