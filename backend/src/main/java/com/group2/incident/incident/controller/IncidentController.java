
package com.group2.incident.incident.controller;

import com.group2.incident.incident.dto.AssignRoleRequest;
import com.group2.incident.incident.dto.CommentRequest;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.CorrectionRequest;
import com.group2.incident.incident.dto.EvidenceRequest;
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
import org.springframework.http.ResponseEntity;
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
        return incidentService.reassignIncident(
                id, request, Long.parseLong(jwt.getSubject()));
    }

    @PutMapping("/{id}/roles")
    @Operation(summary = "Assign or transfer an incident role (LEAD, COMMS, OPS)")
    public ResponseEntity<IncidentResponse> assignRole(
            @PathVariable Long id,
            @Valid @RequestBody AssignRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        Long actorId = Long.parseLong(jwt.getSubject());
        IncidentResponse response =
                incidentService.assignRole(id, request, actorId);

        var responseBuilder = ResponseEntity.ok();

        if (response.warning() != null) {
            responseBuilder.header("X-Warning", response.warning())
                    .header("Warning", "299 - \"" + response.warning() + "\"");
        }

        return responseBuilder.body(response);
    }

    /** Add a comment without changing incident version or status. */
    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a comment to an incident timeline")
    public TimelineEntryResponse addComment(
            @PathVariable Long id,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        return incidentService.addComment(
                id,
                request.comment(),
                Long.parseLong(jwt.getSubject())
        );
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
                id,
                request.url(),
                request.label(),
                Long.parseLong(jwt.getSubject())
        );
    }

    /** Add a correction linked to an existing timeline entry. */
    @PostMapping("/{id}/timeline/{entryId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a correction to an incident timeline entry")
    public TimelineEntryResponse correctTimelineEntry(
            @PathVariable Long id,
            @PathVariable Long entryId,
            @Valid @RequestBody CorrectionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        return incidentService.correctTimelineEntry(
                id,
                entryId,
                request.details(),
                Long.parseLong(jwt.getSubject())
        );
    }
}
