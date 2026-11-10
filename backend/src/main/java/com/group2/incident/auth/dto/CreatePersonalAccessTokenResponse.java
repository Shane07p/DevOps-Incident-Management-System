package com.group2.incident.auth.dto;

import java.time.Instant;

public record CreatePersonalAccessTokenResponse(
		Long id,
		String name,
		String token,
		Instant createdAt,
		Instant expiresAt
) {}

