package com.group2.incident.followup.controller;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.followup.dto.CreateFollowUpRequest;
import com.group2.incident.followup.dto.FollowUpResponse;
import com.group2.incident.followup.dto.UpdateFollowUpRequest;
import com.group2.incident.followup.entity.FollowUpStatus;
import com.group2.incident.followup.service.FollowUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Follow-ups", description = "Action items from incidents: owner, due date, status, and overdue (FR-11.3)")
@RequiredArgsConstructor
public class FollowUpController {

	private final FollowUpService followUpService;

	@GetMapping("/incidents/{id}/follow-ups")
	@Operation(summary = "List an incident's follow-ups, oldest first")
	public List<FollowUpResponse> listForIncident(@PathVariable Long id) {
		return followUpService.listForIncident(id);
	}

	@PostMapping("/incidents/{id}/follow-ups")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Add a follow-up to an incident (Engineer or Admin); the owner must be a member of the org")
	public FollowUpResponse create(
			@PathVariable Long id,
			@Valid @RequestBody CreateFollowUpRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return followUpService.create(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PutMapping("/follow-ups/{id}")
	@Operation(summary = "Change a follow-up's title, kind, owner, due date, or status (Engineer or Admin)")
	public FollowUpResponse update(
			@PathVariable Long id,
			@Valid @RequestBody UpdateFollowUpRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return followUpService.update(id, request, Long.parseLong(jwt.getSubject()));
	}

	@GetMapping("/follow-ups")
	@Operation(summary = "List the org's follow-ups across incidents, soonest due first")
	public List<FollowUpResponse> list(
			@Parameter(description = "\"me\" or a user id") @RequestParam(required = false) String owner,
			@RequestParam(required = false) FollowUpStatus status,
			@Parameter(description = "true: only overdue; false: only the rest") @RequestParam(required = false) Boolean overdue,
			@AuthenticationPrincipal Jwt jwt) {
		return followUpService.list(ownerId(owner, jwt), status, overdue);
	}

	private static Long ownerId(String owner, Jwt jwt) {
		if (owner == null || owner.isBlank()) {
			return null;
		}
		if (owner.equals("me")) {
			return Long.parseLong(jwt.getSubject());
		}
		try {
			return Long.parseLong(owner);
		} catch (NumberFormatException e) {
			throw new FieldValidationException("owner", "Owner must be \"me\" or a user id");
		}
	}
}
