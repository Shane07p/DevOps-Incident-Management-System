package com.settle.insights.dto;

import com.settle.catalog.entity.Severity;

public record SeverityBreakdown(
		Severity severity,
		int count
) {}
