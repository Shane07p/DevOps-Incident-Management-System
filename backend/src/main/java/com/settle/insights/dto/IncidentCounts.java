package com.settle.insights.dto;

public record IncidentCounts(
		int total,
		int open,
		int resolved
) {}
