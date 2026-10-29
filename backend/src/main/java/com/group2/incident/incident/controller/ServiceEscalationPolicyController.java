package com.group2.incident.incident.controller;

import com.group2.incident.incident.dto.EscalationPolicyResponse;
import com.group2.incident.incident.dto.UpdateEscalationPolicyRequest;
import com.group2.incident.incident.service.EscalationPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{id}/escalation-policy")
@Tag(name = "Escalation Policy", description = "Service escalation policies and levels")
@RequiredArgsConstructor
public class ServiceEscalationPolicyController {

	private final EscalationPolicyService escalationPolicyService;

	@GetMapping
	@Operation(summary = "Get the escalation policy for a service")
	public EscalationPolicyResponse get(@PathVariable Long id) {
		return escalationPolicyService.getPolicy(id);
	}

	@PutMapping
	@Operation(summary = "Update the escalation policy for a service (Admin)")
	public EscalationPolicyResponse update(
			@PathVariable Long id,
			@Valid @RequestBody UpdateEscalationPolicyRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		Long actorId = jwt != null ? Long.parseLong(jwt.getSubject()) : null;
		return escalationPolicyService.updatePolicy(id, request, actorId);
	}
}
