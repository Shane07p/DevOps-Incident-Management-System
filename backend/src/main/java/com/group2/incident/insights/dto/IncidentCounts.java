package com.group2.incident.insights.dto;

public record IncidentCounts(
		int total,
		int open,
		int resolved
) {}
