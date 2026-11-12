package com.group2.incident.statuspage.dto;

import com.group2.incident.monitor.service.ServiceHealth;
import com.group2.incident.statuspage.entity.PublicUpdateStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An org's public status page (D45): only the services an Admin picked, under their public names, and only
 * what was published for customers. Never internal titles, notes, comments, or people's names (FR-21.1).
 */
public record PublicStatusResponse(
		String orgName,
		List<PublishedService> services,
		List<PublishedIncident> activeIncidents,
		List<PublishedIncident> resolvedIncidents
) {

	/**
	 * @param uptimePercent passed checks out of all checks over the last 90 days, or null with no checks
	 * @param uptimeDays the last 90 days in UTC, oldest first
	 */
	public record PublishedService(
			Long id,
			String name,
			ServiceHealth health,
			Double uptimePercent,
			List<UptimeDay> uptimeDays
	) {
	}

	/** @param uptimePercent null when no check ran that day */
	public record UptimeDay(LocalDate date, Double uptimePercent) {
	}

	/**
	 * An incident as customers see it, named by its service's public name, with its updates newest first.
	 *
	 * @param status the latest published status
	 * @param resolvedAt when it left the active list; null while active
	 */
	public record PublishedIncident(
			Long id,
			String serviceName,
			PublicUpdateStatus status,
			List<PublicUpdateResponse> updates,
			Instant resolvedAt
	) {
	}
}
