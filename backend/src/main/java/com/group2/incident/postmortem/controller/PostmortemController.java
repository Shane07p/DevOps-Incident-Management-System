package com.group2.incident.postmortem.controller;

import com.group2.incident.postmortem.dto.PostmortemRequest;
import com.group2.incident.postmortem.dto.PostmortemResponse;
import com.group2.incident.postmortem.dto.ReviewPostmortemRequest;
import com.group2.incident.postmortem.service.PostmortemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents/{id}/postmortem")
@Tag(name = "Postmortems", description = "Drafting and independent review after an incident")
@RequiredArgsConstructor
public class PostmortemController {

	private final PostmortemService service;

	@GetMapping
	@Operation(summary = "Get an incident's postmortem")
	public PostmortemResponse get(@PathVariable Long id) {
		return service.get(id);
	}

	@PutMapping
	@Operation(summary = "Save a postmortem as a draft")
	public PostmortemResponse save(@PathVariable Long id, @Valid @RequestBody PostmortemRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return service.save(id, request, Long.parseLong(jwt.getSubject()));
	}

	@PostMapping("/review")
	@Operation(summary = "Review another responder's postmortem")
	public PostmortemResponse review(@PathVariable Long id, @Valid @RequestBody ReviewPostmortemRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return service.review(id, request.expectedVersion(), Long.parseLong(jwt.getSubject()));
	}
}
