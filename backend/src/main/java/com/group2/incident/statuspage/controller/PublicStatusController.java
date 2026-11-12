package com.group2.incident.statuspage.controller;

import com.group2.incident.statuspage.dto.PublicStatusResponse;
import com.group2.incident.statuspage.service.PublicStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/status")
@Tag(name = "Public status page", description = "An org's status page for its customers; needs no login (D45)")
@RequiredArgsConstructor
public class PublicStatusController {

	private final PublicStatusService publicStatusService;

	@GetMapping("/{slug}")
	@Operation(summary = "Read an org's public status page: its published services, uptime, and public updates")
	public PublicStatusResponse statusPage(@PathVariable String slug) {
		return publicStatusService.statusPage(slug);
	}
}
