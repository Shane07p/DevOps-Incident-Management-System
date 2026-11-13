package com.group2.incident.telemetry.controller;

import com.group2.incident.telemetry.dto.TelemetryConnectionRequest;
import com.group2.incident.telemetry.dto.TelemetryConnectionResponse;
import com.group2.incident.telemetry.dto.TelemetryTestResponse;
import com.group2.incident.telemetry.service.TelemetryConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The org's own Grafana connection (D49, #260). Admin only; the token goes in and never comes back out. */
@RestController
@RequestMapping("/api/settings/telemetry")
@Tag(name = "Telemetry connection", description = "Connect the organization's own Grafana for logs and metrics")
@RequiredArgsConstructor
public class TelemetryConnectionController {

	private final TelemetryConnectionService connectionService;

	@GetMapping
	@Operation(summary = "Get the organization's Grafana connection, without its token")
	public TelemetryConnectionResponse get() {
		return connectionService.get();
	}

	@PutMapping
	@Operation(summary = "Connect or change the organization's Grafana")
	public TelemetryConnectionResponse save(
			@Valid @RequestBody TelemetryConnectionRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		return connectionService.save(request, Long.parseLong(jwt.getSubject()));
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Disconnect the organization's Grafana")
	public void delete(@AuthenticationPrincipal Jwt jwt) {
		connectionService.delete(Long.parseLong(jwt.getSubject()));
	}

	@PostMapping("/test")
	@Operation(summary = "Try the saved Grafana connection")
	public TelemetryTestResponse test() {
		return connectionService.test();
	}
}
