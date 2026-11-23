package com.settle.postmortem.dto;

import com.settle.auth.entity.AppUser;
import com.settle.incident.dto.IncidentUserDto;
import com.settle.postmortem.entity.Postmortem;
import com.settle.postmortem.entity.PostmortemStatus;
import java.time.Instant;

public record PostmortemResponse(
		long id, long incidentId, PostmortemStatus status,
		String summary, String impact, String rootCause, String resolution, String wentWell, String wentBadly,
		IncidentUserDto author, IncidentUserDto reviewedBy,
		Instant reviewedAt, Instant updatedAt, long version
) {
	public static PostmortemResponse from(Postmortem postmortem) {
		return new PostmortemResponse(postmortem.getId(), postmortem.getIncident().getId(), postmortem.getStatus(),
				postmortem.getSummary(), postmortem.getImpact(), postmortem.getRootCause(), postmortem.getResolution(),
				postmortem.getWentWell(), postmortem.getWentBadly(), person(postmortem.getAuthor()),
				person(postmortem.getReviewedBy()), postmortem.getReviewedAt(), postmortem.getUpdatedAt(), postmortem.getVersion());
	}

	private static IncidentUserDto person(AppUser user) {
		return user == null ? null : new IncidentUserDto(user.getId(), user.getDisplayName());
	}
}
