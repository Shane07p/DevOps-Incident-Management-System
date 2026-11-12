package com.group2.incident.statuspage.dto;

import com.group2.incident.statuspage.entity.PublicUpdate;
import com.group2.incident.statuspage.entity.PublicUpdateStatus;
import java.time.Instant;

/** A published update as customers see it: no author, no internal fields. */
public record PublicUpdateResponse(
		Long id,
		PublicUpdateStatus status,
		String message,
		Instant createdAt
) {
	public static PublicUpdateResponse from(PublicUpdate update) {
		return new PublicUpdateResponse(update.getId(), update.getStatus(), update.getMessage(), update.getCreatedAt());
	}
}
