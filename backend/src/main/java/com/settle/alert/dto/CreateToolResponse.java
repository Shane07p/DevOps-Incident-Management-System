package com.settle.alert.dto;

import java.time.Instant;

public record CreateToolResponse(
		Long id,
		String name,
		String token,
		Instant createdAt
) {}
