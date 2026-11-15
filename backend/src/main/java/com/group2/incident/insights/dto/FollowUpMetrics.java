package com.group2.incident.insights.dto;

public record FollowUpMetrics(
		int total,
		int done,
		int overdue,
		Double completionRate
) {}
