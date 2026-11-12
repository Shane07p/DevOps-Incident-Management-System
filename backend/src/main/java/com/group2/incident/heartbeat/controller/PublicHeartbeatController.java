package com.group2.incident.heartbeat.controller;

import com.group2.incident.heartbeat.dto.HeartbeatPingResponse;
import com.group2.incident.heartbeat.service.HeartbeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/heartbeats")
@Tag(name = "Public Heartbeats", description = "Public ping endpoint for scheduled jobs; needs no login (FR-22)")
@RequiredArgsConstructor
public class PublicHeartbeatController {

	private final HeartbeatService heartbeatService;

	@PostMapping("/ping/{token}")
	@Operation(summary = "Record a heartbeat ping from a scheduled job (POST)")
	public HeartbeatPingResponse pingPost(@PathVariable String token) {
		return heartbeatService.ping(token);
	}

	@GetMapping("/ping/{token}")
	@Operation(summary = "Record a heartbeat ping from a scheduled job (GET)")
	public HeartbeatPingResponse pingGet(@PathVariable String token) {
		return heartbeatService.ping(token);
	}
}
