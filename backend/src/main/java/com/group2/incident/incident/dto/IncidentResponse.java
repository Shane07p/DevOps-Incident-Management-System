package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.deploy.dto.DeployResponse;
import com.group2.incident.incident.entity.IncidentStatus;
import java.time.Instant;
import java.util.List;

/**
 * One incident with its timeline. The assignee is the Lead (D39); {@code roles} holds Comms and Ops.
 * {@code roleWarning} is set while one person holds more than one role on a SEV1 or SEV2 incident
 * (FR-05.5), and null otherwise. {@code version} goes back as {@code expectedVersion} on a change, so
 * a change made from an out-of-date screen is refused (NFR-11.2). {@code acknowledgedAt} is when the
 * first accepted acknowledgement stopped the response clock (FR-16.2), and null while it runs.
 */
public record IncidentResponse(
		long id,
		String reference,
		String title,
		String description,
		IncidentServiceDto service,
		Severity severity,
		IncidentStatus status,
		long version,
		IncidentUserDto reporter,
		IncidentUserDto assignee,
		IncidentRolesDto roles,
		String roleWarning,
		Instant createdAt,
		int responseTargetMinutes,
		int resolutionTargetMinutes,
		Instant responseDueAt,
		Instant resolutionDueAt,
		Instant acknowledgedAt,
		List<TimelineEntryResponse> timeline,
		List<DeployResponse> deploys
) {
	public IncidentResponse {
		if (timeline == null) {
			timeline = List.of();
		}
		if (deploys == null) {
			deploys = List.of();
		}
	}

	public IncidentResponse(
			long id,
			String reference,
			String title,
			String description,
			IncidentServiceDto service,
			Severity severity,
			IncidentStatus status,
			long version,
			IncidentUserDto reporter,
			IncidentUserDto assignee,
			IncidentRolesDto roles,
			String roleWarning,
			Instant createdAt,
			int responseTargetMinutes,
			int resolutionTargetMinutes,
			Instant responseDueAt,
			Instant resolutionDueAt,
			Instant acknowledgedAt,
			List<TimelineEntryResponse> timeline
	) {
		this(
				id,
				reference,
				title,
				description,
				service,
				severity,
				status,
				version,
				reporter,
				assignee,
				roles,
				roleWarning,
				createdAt,
				responseTargetMinutes,
				resolutionTargetMinutes,
				responseDueAt,
				resolutionDueAt,
				acknowledgedAt,
				timeline,
				List.of()
		);
	}
}
