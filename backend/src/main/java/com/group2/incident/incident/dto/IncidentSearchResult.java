package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.IncidentStatus;
import java.time.Instant;
import java.util.List;

public record IncidentSearchResult(
		long id,
		String reference,
		String title,
		String serviceName,
		Severity severity,
		IncidentStatus status,
		Instant createdAt,
		List<String> matchedIn
) {
}
