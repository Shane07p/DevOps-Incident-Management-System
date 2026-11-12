package com.group2.incident.alert.controller;

import com.group2.incident.alert.dto.GrafanaWebhookRequest;
import com.group2.incident.alert.dto.GrafanaWebhookResponse;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.security.ToolTokenInterceptor;
import com.group2.incident.alert.service.GrafanaAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/grafana")
@Tag(name = "Grafana Webhook", description = "Grafana Alerting's own webhook format into alert intake")
@RequiredArgsConstructor
public class GrafanaWebhookController {

	private final GrafanaAlertService grafanaAlertService;

	// ToolTokenInterceptor has already checked the Authorization header and put the tool here.
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Receive a Grafana Alerting notification in Grafana's own webhook format")
	public GrafanaWebhookResponse receive(
			@RequestAttribute(ToolTokenInterceptor.TOOL_ATTRIBUTE) MonitoringTool tool,
			@RequestBody GrafanaWebhookRequest notification
	) {
		return grafanaAlertService.receive(tool, notification);
	}
}
