package com.group2.incident.deploy.controller;

import com.group2.incident.deploy.dto.DeployResponse;
import com.group2.incident.deploy.service.DeploymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{id}/deploys")
@Tag(name = "Service Deployments", description = "Deployment history per service")
@RequiredArgsConstructor
public class ServiceDeploymentController {

	private final DeploymentService deploymentService;

	@GetMapping
	@Operation(summary = "List a service's deployments, newest first")
	public List<DeployResponse> listDeploys(@PathVariable Long id) {
		return deploymentService.listServiceDeploys(id);
	}
}
