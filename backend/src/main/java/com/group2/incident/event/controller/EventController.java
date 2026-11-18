package com.group2.incident.event.controller;

import com.group2.incident.common.error.UnauthorizedException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.event.service.EventStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams real-time updates to signed-in users using Server-Sent Events (FR-12.3, NFR-14, D46).
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Server-sent events for real-time updates")
@RequiredArgsConstructor
public class EventController {

	private final EventStreamService eventStreamService;

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(summary = "Stream server-sent events for the signed-in user's organization")
	public SseEmitter streamEvents() {
		long orgId = OrgContext.current();
		if (orgId <= 0) {
			throw new UnauthorizedException("Authentication required");
		}
		return eventStreamService.subscribe(orgId);
	}
}
