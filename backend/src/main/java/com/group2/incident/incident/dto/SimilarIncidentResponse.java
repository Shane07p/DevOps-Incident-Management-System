package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.IncidentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimilarIncidentResponse(
		long id,
		String reference,
		String title,
		String serviceName,
		Severity severity,
		IncidentStatus status,
		double score,
		String fix
) {
}
