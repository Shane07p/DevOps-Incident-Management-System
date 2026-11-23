package com.settle.auth.dto;

import com.settle.auth.entity.PersonalAccessToken;
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

