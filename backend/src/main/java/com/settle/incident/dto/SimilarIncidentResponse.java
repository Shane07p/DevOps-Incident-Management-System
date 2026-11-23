package com.settle.incident.dto;

import com.settle.catalog.entity.Severity;
import com.settle.incident.entity.IncidentStatus;
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
