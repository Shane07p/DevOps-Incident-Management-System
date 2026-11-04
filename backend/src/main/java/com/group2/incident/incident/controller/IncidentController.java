package com.group2.incident.incident.controller;

import com.group2.incident.incident.dto.CommentRequest;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.CorrectionRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.ReassignRequest;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.service.IncidentService;
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

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents", description = "Incident declaration, tracking, and detail")
@RequiredArgsConstructor
public class IncidentController {

	private final IncidentService incidentService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an incident")
	public IncidentResponse createIncident(
			@Valid @RequestBody CreateIncidentRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		Long reporterId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
		return incidentService.createIncident(request, reporterId);
	}

	@GetMapping
	@Operation(summary = "List all incidents, newest first")
	public List<IncidentSummaryResponse> listIncidents() {
		return incidentService.listIncidents();
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get incident details including timeline")
	public IncidentResponse getIncident(@PathVariable Long id) {
		return incidentService.getIncident(id);
	}

	@PutMapping("/{id}/assignee")
	@Operation(summary = "Reassign an incident to a new owner")
	public IncidentResponse reassign(
			@PathVariable Long id,
			@Valid @RequestBody ReassignRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.reassignIncident(id, request, Long.parseLong(jwt.getSubject()));
	}

	/** Adds a separate timeline entry without changing the incident version or status. */
	@PostMapping("/{id}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Add a comment to an incident timeline")
	public TimelineEntryResponse addComment(
			@PathVariable Long id,
			@Valid @RequestBody CommentRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.addComment(id, request.comment(), Long.parseLong(jwt.getSubject()));
	}

	/** Appends a correction linked to an entry on this incident's timeline. */
	@PostMapping("/{id}/timeline/{entryId}/corrections")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Add a correction to an incident timeline entry")
	public TimelineEntryResponse correctTimelineEntry(
			@PathVariable Long id,
			@PathVariable Long entryId,
			@Valid @RequestBody CorrectionRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.correctTimelineEntry(
				id, entryId, request.details(), Long.parseLong(jwt.getSubject()));
	}
}
