package com.group2.incident.heartbeat.controller;

import com.group2.incident.heartbeat.dto.HeartbeatRequest;
import com.group2.incident.heartbeat.dto.HeartbeatResponse;
import com.group2.incident.heartbeat.service.HeartbeatService;
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

@Tag(name = "Heartbeats", description = "Scheduled job heartbeats per service (FR-22)")
@RestController
@RequestMapping("/api/heartbeats")
@RequiredArgsConstructor
public class HeartbeatController {

	private final HeartbeatService heartbeatService;

	@GetMapping
	@Operation(summary = "List the heartbeats")
	public List<HeartbeatResponse> list() {
		return heartbeatService.list();
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a heartbeat by id")
	public HeartbeatResponse get(@PathVariable Long id) {
		return heartbeatService.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a heartbeat (Admin)")
	public HeartbeatResponse create(@Valid @RequestBody HeartbeatRequest request, @AuthenticationPrincipal Jwt jwt) {
		return heartbeatService.create(request, actorId(jwt));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Change a heartbeat (Admin)")
	public HeartbeatResponse update(@PathVariable Long id, @Valid @RequestBody HeartbeatRequest request, @AuthenticationPrincipal Jwt jwt) {
		return heartbeatService.update(id, request, actorId(jwt));
	}

	private static Long actorId(Jwt jwt) {
		return jwt != null ? Long.parseLong(jwt.getSubject()) : null;
	}
}
