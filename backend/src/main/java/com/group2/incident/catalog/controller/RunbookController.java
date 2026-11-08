package com.group2.incident.catalog.controller;

import com.group2.incident.catalog.dto.RunbookRequest;
import com.group2.incident.catalog.dto.RunbookResponse;
import com.group2.incident.catalog.service.RunbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Runbooks", description = "Step-by-step fixes kept per service")
@RequiredArgsConstructor
public class RunbookController {

	private final RunbookService runbookService;

	@GetMapping("/services/{id}/runbooks")
	@Operation(summary = "List a service's runbooks")
	public List<RunbookResponse> list(@PathVariable Long id) {
		return runbookService.list(id);
	}

	@PostMapping("/services/{id}/runbooks")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Write a runbook for a service (Engineer or Admin)")
	public RunbookResponse create(@PathVariable Long id, @Valid @RequestBody RunbookRequest request, @AuthenticationPrincipal Jwt jwt) {
		return runbookService.create(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PutMapping("/runbooks/{id}")
	@Operation(summary = "Change a runbook (Engineer or Admin)")
	public RunbookResponse update(@PathVariable Long id, @Valid @RequestBody RunbookRequest request, @AuthenticationPrincipal Jwt jwt) {
		return runbookService.update(id, request, Long.parseLong(jwt.getSubject()));
	}

	@DeleteMapping("/runbooks/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a runbook (Engineer or Admin)")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		runbookService.delete(id, Long.parseLong(jwt.getSubject()));
	}
}
