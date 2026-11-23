package com.settle.deploy.controller;

import com.settle.alert.entity.MonitoringTool;
import com.settle.alert.security.ToolTokenInterceptor;
import com.settle.deploy.dto.DeployWebhookRequest;
import com.settle.deploy.dto.DeployWebhookResponse;
import com.settle.deploy.service.DeploymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/deploys")
@Tag(name = "Deployments Webhook", description = "Incoming deployment webhook intake")
@RequiredArgsConstructor
public class DeployWebhookController {

	private final DeploymentService deploymentService;

	// ToolTokenInterceptor has already verified Authorization header and tool rate limits.
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Record a deployment from a registered tool")
	public DeployWebhookResponse receiveDeploy(
			@RequestAttribute(ToolTokenInterceptor.TOOL_ATTRIBUTE) MonitoringTool tool,
			@Valid @RequestBody DeployWebhookRequest request
	) {
		return deploymentService.processDeploy(tool, request);
	}
}
