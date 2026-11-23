package com.settle.incident.dto;

import java.util.List;

public record IncidentSearchPageResponse(
		List<IncidentSearchResult> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
