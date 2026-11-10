package com.group2.incident.statuspage.controller;

import com.group2.incident.statuspage.dto.PublicUpdateRequest;
import com.group2.incident.statuspage.dto.PublicUpdateResponse;
import com.group2.incident.statuspage.service.PublicUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Public updates", description = "Customer-facing incident updates, kept apart from internal notes (FR-21)")
@RequiredArgsConstructor
public class PublicUpdateController {

	private final PublicUpdateService publicUpdateService;

	@PostMapping("/{id}/public-updates")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Publish a customer-facing update on an incident (its Lead or Comms, or an Admin)")
	public PublicUpdateResponse publish(
			@PathVariable Long id,
			@Valid @RequestBody PublicUpdateRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return publicUpdateService.publish(id, request, Long.parseLong(jwt.getSubject()));
	}
}
