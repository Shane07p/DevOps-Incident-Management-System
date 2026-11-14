package com.group2.incident.insights.dto;

public record ServiceBreakdown(
		Long serviceId,
		String name,
		int count
) {}
