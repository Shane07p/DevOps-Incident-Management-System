package com.settle.insights.dto;

public record ServiceBreakdown(
		Long serviceId,
		String name,
		int count
) {}
