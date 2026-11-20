package com.group2.incident.investigation.dto;

import java.util.List;

public record InvestigationHypothesisDto(
		int rank,
		String title,
		double confidence,
		List<InvestigationEvidenceDto> evidence
) {
}
