package com.group2.incident.incident.dto;

import com.group2.incident.incident.entity.IncidentStatus;

public record DuplicateIncidentResponse(
		long id,
		String reference,
		String title,
		IncidentStatus status,
		double score
) {
}
