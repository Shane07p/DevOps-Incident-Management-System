package com.group2.incident.alert.controller;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.security.ToolTokenInterceptor;
import com.group2.incident.alert.service.AlertIntakeService;
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
@RequestMapping("/webhooks/alerts")
@Tag(name = "Alerts Webhook", description = "Incoming alerts webhook intake")
@RequiredArgsConstructor
public class AlertWebhookController {

	private final AlertIntakeService alertIntakeService;

	// ToolTokenInterceptor has already checked the Authorization header and put the tool here.
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Receive and validate an alert from a registered monitoring tool")
	public AlertAcceptedResponse receiveAlert(
			@RequestAttribute(ToolTokenInterceptor.TOOL_ATTRIBUTE) MonitoringTool tool,
			@Valid @RequestBody AlertRequest request
	) {
		return alertIntakeService.processAlert(tool, request);
	}
}
