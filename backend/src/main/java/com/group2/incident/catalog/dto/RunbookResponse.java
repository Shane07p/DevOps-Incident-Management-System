package com.group2.incident.catalog.dto;

import com.group2.incident.catalog.entity.Runbook;
import java.time.Instant;
import java.util.List;

public record RunbookResponse(
		Long id,
		Long serviceId,
		String title,
		List<String> steps,
		Instant createdAt,
		Instant updatedAt
) {

	public static RunbookResponse from(Runbook runbook) {
		return new RunbookResponse(
				runbook.getId(),
				runbook.getService().getId(),
				runbook.getTitle(),
				runbook.getSteps(),
				runbook.getCreatedAt(),
				runbook.getUpdatedAt()
		);
	}
}
