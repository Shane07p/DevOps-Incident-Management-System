package com.group2.incident.investigation.controller;

import com.group2.incident.investigation.dto.ActionDecisionRequest;
import com.group2.incident.investigation.dto.InvestigationResponse;
import com.group2.incident.investigation.dto.SuggestedActionDto;
import com.group2.incident.investigation.service.InvestigationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Investigations", description = "AI SRE incident investigations and action decisions")
@RequiredArgsConstructor
public class InvestigationController {

	private final InvestigationService investigationService;

	@PostMapping("/incidents/{id}/investigations")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Run an AI SRE investigation on an incident")
	public InvestigationResponse investigate(
			@PathVariable Long id,
			@AuthenticationPrincipal Jwt jwt
	) {
		return investigationService.investigate(id, Long.parseLong(jwt.getSubject()));
	}

	@GetMapping("/incidents/{id}/investigations/latest")
	@Operation(summary = "Get the latest AI SRE investigation for an incident")
	public InvestigationResponse getLatest(@PathVariable Long id) {
		return investigationService.getLatest(id);
	}

	@PostMapping("/investigations/{id}/actions/{actionId}/decision")
	@Operation(summary = "Accept or reject an AI-suggested investigation action without executing it")
	public SuggestedActionDto decideAction(
			@PathVariable Long id,
			@PathVariable Long actionId,
			@Valid @RequestBody ActionDecisionRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		return investigationService.decideAction(id, actionId, request.decision(), Long.parseLong(jwt.getSubject()));
	}
}
