package com.group2.incident.incident.dto;

import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.incident.entity.IncidentStatus;
import java.time.Instant;

/**
 * One row of the incident board. The due times let the board sort by time left; which clock still runs
 * follows from the status (OPEN: response; until RESOLVED: resolution). The version lets the overview
 * acknowledge from the list (a stale one gets 409), the assignee is the Lead (D39), and the stop times
 * say whether each stopped clock was met.
 */
public record IncidentSummaryResponse(
		long id,
		String reference,
		String title,
		String serviceName,
		Severity severity,
		IncidentStatus status,
		Instant createdAt,
		Instant responseDueAt,
		Instant resolutionDueAt,
		long version,
		IncidentUserDto assignee,
		Instant acknowledgedAt,
		Instant resolvedAt
) {
}
