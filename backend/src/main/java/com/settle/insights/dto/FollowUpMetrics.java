package com.settle.insights.dto;

public record FollowUpMetrics(
		int total,
		int done,
		int overdue,
		Double completionRate
) {}
