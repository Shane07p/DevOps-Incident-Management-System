package com.group2.incident.incident.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record DuplicateCheckResponse(
		boolean duplicate,
		@JsonInclude(JsonInclude.Include.ALWAYS) DuplicateIncidentResponse incident,
		@JsonInclude(JsonInclude.Include.NON_NULL) String message
) {
}
