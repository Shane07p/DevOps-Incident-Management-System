package com.group2.incident.insights.controller;

import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.insights.dto.InsightsResponse;
import com.group2.incident.insights.service.InsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
@Tag(name = "Insights", description = "Operational metrics, SLA performance, and trends")
public class InsightsController {

	private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);

	private final InsightsService insightsService;

	@GetMapping
	@Operation(
			summary = "Get operational insights and metrics",
			description = "Returns aggregated incident, SLA, follow-up, and pager metrics for 7, 30, or 90 days."
	)
	public InsightsResponse getInsights(@RequestParam(name = "days", defaultValue = "30") int days) {
		if (!ALLOWED_DAYS.contains(days)) {
			throw new FieldValidationException("days", "Must be 7, 30, or 90");
		}
		return insightsService.getInsights(days);
	}
}
