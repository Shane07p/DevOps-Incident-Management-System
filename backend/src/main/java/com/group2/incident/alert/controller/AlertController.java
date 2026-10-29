package com.group2.incident.alert.controller;

import com.group2.incident.alert.dto.AlertInboxItemResponse;
import com.group2.incident.alert.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alerts", description = "Alert inbox and incident grouping")
@RequiredArgsConstructor
public class AlertController {

	private final AlertService alertService;

	@GetMapping
	@Operation(summary = "List alerts for the inbox")
	public List<AlertInboxItemResponse> getInboxAlerts() {
		return alertService.getInboxAlerts();
	}
}
