package com.settle.incident.dto;

import com.settle.catalog.entity.Severity;
import com.settle.incident.entity.IncidentStatus;
import java.time.Instant;
import java.util.List;

public record IncidentSearchResult(
		long id,
		String reference,
		long serviceId,
		String title,
		String serviceName,
		Severity severity,
		IncidentStatus status,
		Instant createdAt,
		List<String> matchedIn
) {
}
