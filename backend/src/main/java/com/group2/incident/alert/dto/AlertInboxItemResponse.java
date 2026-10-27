package com.group2.incident.alert.dto;

import java.time.Instant;

public record AlertInboxItemResponse(
		Long id,
		String source,
		String serviceName,
		Long incidentId,
		String status,
		String correlationKey,
		Instant createdAt
) {}
