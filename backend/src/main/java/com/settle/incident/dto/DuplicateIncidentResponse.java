package com.settle.incident.dto;

import com.settle.incident.entity.IncidentStatus;

public record DuplicateIncidentResponse(
		long id,
		String reference,
		String title,
		IncidentStatus status,
		double score
) {
}
