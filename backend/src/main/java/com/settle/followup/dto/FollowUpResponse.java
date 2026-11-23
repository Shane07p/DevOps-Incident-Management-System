package com.settle.followup.dto;

import com.settle.followup.entity.FollowUp;
import com.settle.followup.entity.FollowUpKind;
import com.settle.followup.entity.FollowUpStatus;
import com.settle.incident.dto.IncidentUserDto;
import java.time.Instant;
import java.time.LocalDate;

/** A follow-up as every list and form shows it; {@code overdue} is worked out against today (UTC). */
public record FollowUpResponse(
		Long id,
		Long incidentId,
		String incidentReference,
		String title,
		FollowUpKind kind,
		IncidentUserDto owner,
		LocalDate dueDate,
		FollowUpStatus status,
		boolean overdue,
		Instant completedAt,
		Instant createdAt
) {
	public static FollowUpResponse from(FollowUp followUp, LocalDate today) {
		return new FollowUpResponse(
				followUp.getId(),
				followUp.getIncident().getId(),
				followUp.getIncident().getReference(),
				followUp.getTitle(),
				followUp.getKind(),
				new IncidentUserDto(followUp.getOwner().getId(), followUp.getOwner().getDisplayName()),
				followUp.getDueDate(),
				followUp.getStatus(),
				followUp.isOverdue(today),
				followUp.getCompletedAt(),
				followUp.getCreatedAt());
	}
}
