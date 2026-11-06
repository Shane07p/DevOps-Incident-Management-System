package com.group2.incident.monitor.controller;

import com.group2.incident.monitor.dto.MonitorRequest;
import com.group2.incident.monitor.dto.MonitorResponse;
import com.group2.incident.monitor.service.MonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monitors", description = "HTTP uptime checks per service (FR-22)")
@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
public class MonitorController {

	private final MonitorService monitorService;

	@GetMapping
	@Operation(summary = "List the uptime monitors")
	public List<MonitorResponse> list() {
		return monitorService.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an uptime monitor (Admin)")
	public MonitorResponse create(@Valid @RequestBody MonitorRequest request, @AuthenticationPrincipal Jwt jwt) {
		return monitorService.create(request, actorId(jwt));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Change an uptime monitor (Admin)")
	public MonitorResponse update(@PathVariable Long id, @Valid @RequestBody MonitorRequest request, @AuthenticationPrincipal Jwt jwt) {
		return monitorService.update(id, request, actorId(jwt));
	}

	private static Long actorId(Jwt jwt) {
		return jwt != null ? Long.parseLong(jwt.getSubject()) : null;
	}
}
