package com.group2.incident.alert.controller;

import com.group2.incident.alert.dto.CreateToolRequest;
import com.group2.incident.alert.dto.CreateToolResponse;
import com.group2.incident.alert.dto.ToolResponse;
import com.group2.incident.alert.service.ToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
@Tag(name = "Tools", description = "Monitoring tool registration and token management (Admin only)")
@RequiredArgsConstructor
public class ToolController {

	private final ToolService toolService;

	@GetMapping
	@Operation(summary = "List all registered monitoring tools without tokens")
	public List<ToolResponse> listTools() {
		return toolService.listTools();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Register a monitoring tool and generate its bearer token (shown once)")
	public CreateToolResponse createTool(
			@Valid @RequestBody CreateToolRequest request,
			@AuthenticationPrincipal Jwt jwt
	) {
		return toolService.createTool(request, userId(jwt));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Revoke a monitoring tool token")
	public void revokeTool(
			@PathVariable long id,
			@AuthenticationPrincipal Jwt jwt
	) {
		toolService.revokeTool(id, userId(jwt));
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}
}
