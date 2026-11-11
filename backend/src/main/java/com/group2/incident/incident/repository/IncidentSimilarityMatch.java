package com.group2.incident.incident.repository;

import java.time.Instant;

public record IncidentSimilarityMatch(
		long id,
		long number,
		long serviceId,
		String title,
		String serviceName,
		String severity,
		String status,
		Instant createdAt,
		double score
) {
}
