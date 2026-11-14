package com.group2.incident.insights.dto;

import java.time.Instant;
import java.util.List;

public record InsightsResponse(
		Instant from,
		Instant to,
		IncidentCounts incidents,
		MttxMetrics mtta,
		MttxMetrics mttr,
		List<SeverityBreakdown> bySeverity,
		List<ServiceBreakdown> byService,
		SlaBreachRate slaBreachRate,
		FollowUpMetrics followUps,
		int outOfHoursPages
) {
	public InsightsResponse {
		if (bySeverity == null) {
			bySeverity = List.of();
		}
		if (byService == null) {
			byService = List.of();
		}
	}
}
