package com.group2.incident.insights.dto;

import com.group2.incident.catalog.entity.Severity;

public record SeverityBreakdown(
		Severity severity,
		int count
) {}
