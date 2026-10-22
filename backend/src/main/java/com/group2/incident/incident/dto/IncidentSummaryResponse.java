package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import java.time.Instant;

public record IncidentSummaryResponse(
		long id,
		String reference,
		String title,
		String serviceName,
		Severity severity,
		IncidentStatus status,
		Instant createdAt
) {
}
