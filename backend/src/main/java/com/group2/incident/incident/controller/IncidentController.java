package com.group2.incident.incident.controller;

import com.group2.incident.incident.dto.AcknowledgeRequest;
import com.group2.incident.incident.dto.AssignRoleRequest;
import com.group2.incident.incident.dto.CommentRequest;
import com.group2.incident.incident.dto.EvidenceRequest;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.CorrectionRequest;
import com.group2.incident.incident.dto.DuplicateCheckResponse;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentSearchPageResponse;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.ReassignRequest;
import com.group2.incident.incident.dto.SimilarIncidentResponse;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.dto.TransitionRequest;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.incident.service.IncidentSimilarityService;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.incident.entity.IncidentStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents", description = "Incident declaration, tracking, and detail")
@RequiredArgsConstructor
public class IncidentController {

	private final IncidentService incidentService;
	private final IncidentSimilarityService incidentSimilarityService;

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

	/** Returns matching incidents with pagination metadata and matched text fields. */
	@GetMapping("/search")
	@Operation(summary = "Search incidents by keyword, service, severity, and status")
	public IncidentSearchPageResponse searchIncidents(
			@RequestParam(value = "q", required = false) String query,
			@RequestParam(value = "serviceId", required = false) Long serviceId,
			@RequestParam(value = "severity", required = false) Severity severity,
			@RequestParam(value = "status", required = false) IncidentStatus status,
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "mode", defaultValue = "keyword") String mode) {
		if ("semantic".equals(mode) && query != null && !query.isBlank()) {
			return incidentSimilarityService.searchByMeaning(query, serviceId, severity, status, page);
		}
		if ("keyword".equals(mode) || "semantic".equals(mode)) {
			return incidentService.searchIncidents(query, serviceId, severity, status, page);
		}
		throw new FieldValidationException("mode", "must be keyword or semantic");
	}

	@GetMapping("/duplicate-check")
	@Operation(summary = "Check whether a title resembles an open incident")
	public DuplicateCheckResponse checkDuplicate(
			@RequestParam("title") String title,
			@RequestParam(value = "serviceId", required = false) Long serviceId) {
		return incidentSimilarityService.checkDuplicate(title, serviceId);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get incident details including timeline")
	public IncidentResponse getIncident(@PathVariable Long id) {
		return incidentService.getIncident(id);
	}

	@GetMapping("/{id}/similar")
	@Operation(summary = "Find similar incidents in the caller's organization")
	public List<SimilarIncidentResponse> findSimilarIncidents(@PathVariable Long id) {
		return incidentSimilarityService.findSimilarIncidents(id);
	}

	@PutMapping("/{id}/assignee")
	@Operation(summary = "Reassign an incident to a new owner")
	public IncidentResponse reassign(
			@PathVariable Long id,
			@Valid @RequestBody ReassignRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.reassignIncident(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PostMapping("/{id}/acknowledge")
	@Operation(summary = "Acknowledge an incident")
	public IncidentResponse acknowledge(
			@PathVariable Long id,
			@Valid @RequestBody AcknowledgeRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.acknowledgeIncident(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PostMapping("/{id}/transitions")
	@Operation(summary = "Transition an incident to a new status")
	public IncidentResponse transition(
			@PathVariable Long id,
			@Valid @RequestBody TransitionRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.transitionIncident(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PutMapping("/{id}/roles")
	@Operation(summary = "Give the Comms or Ops role to a person; the Lead is the assignee")
	public IncidentResponse assignRole(
			@PathVariable Long id,
			@Valid @RequestBody AssignRoleRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.assignRole(id, request, Long.parseLong(jwt.getSubject()));
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

	/** Attach an evidence link to an incident. */
	@PostMapping("/{id}/evidence")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Attach an evidence link to an incident")
	public TimelineEntryResponse addEvidence(
			@PathVariable Long id,
			@Valid @RequestBody EvidenceRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.addEvidence(
				id, request.url(), request.label(), Long.parseLong(jwt.getSubject()));
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

	/** Links a deployment to an incident timeline. */
	@PostMapping("/{id}/deploys/{deployId}")
	@Operation(summary = "Link a deployment to an incident timeline")
	public TimelineEntryResponse linkDeployment(
			@PathVariable Long id,
			@PathVariable Long deployId,
			@AuthenticationPrincipal Jwt jwt) {
		return incidentService.linkDeployment(id, deployId, Long.parseLong(jwt.getSubject()));
	}
}
