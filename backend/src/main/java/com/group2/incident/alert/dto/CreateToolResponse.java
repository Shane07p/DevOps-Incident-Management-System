package com.group2.incident.alert.dto;

import java.time.Instant;

public record CreateToolResponse(
		Long id,
		String name,
		String token,
		Instant createdAt
) {}
