package com.group2.incident.auth.dto;

import com.group2.incident.auth.entity.PersonalAccessToken;
import java.time.Instant;

public record PersonalAccessTokenResponse(
		Long id,
		String name,
		Instant createdAt,
		Instant expiresAt,
		Instant lastUsedAt,
		Instant revokedAt
) {
	public static PersonalAccessTokenResponse from(PersonalAccessToken token) {
		return new PersonalAccessTokenResponse(
				token.getId(),
				token.getName(),
				token.getCreatedAt(),
				token.getExpiresAt(),
				token.getLastUsedAt(),
				token.getRevokedAt()
		);
	}
}

