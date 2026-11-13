package com.group2.incident.incident.service;

import com.group2.incident.incident.entity.IncidentStatus;
import org.springframework.stereotype.Component;

/**
 * Checks allowed status transitions according to AGENTS.md domain rules and DR-03.
 *
 * Legal transitions (8 pairs):
 * 1. OPEN -> ACKNOWLEDGED
 * 2. ACKNOWLEDGED -> INVESTIGATING
 * 3. INVESTIGATING -> IDENTIFIED
 * 4. INVESTIGATING -> MONITORING
 * 5. IDENTIFIED -> MONITORING
 * 6. MONITORING -> INVESTIGATING
 * 7. MONITORING -> RESOLVED
 * 8. RESOLVED -> CLOSED
 *
 * All other 41 transitions are rejected.
 */
@Component
public class TransitionPolicy {

	/**
	 * Determines whether transitioning an incident from {@code from} to {@code to} is legal.
	 *
	 * @param from current status of the incident
	 * @param to requested new status
	 * @return true if the transition is legal, false otherwise
	 */
	public boolean isLegal(IncidentStatus from, IncidentStatus to) {
		if (from == null || to == null) {
			return false;
		}
		return switch (from) {
			case OPEN -> to == IncidentStatus.ACKNOWLEDGED;
			case ACKNOWLEDGED -> to == IncidentStatus.INVESTIGATING;
			case INVESTIGATING -> to == IncidentStatus.IDENTIFIED || to == IncidentStatus.MONITORING;
			case IDENTIFIED -> to == IncidentStatus.MONITORING;
			case MONITORING -> to == IncidentStatus.INVESTIGATING || to == IncidentStatus.RESOLVED;
			case RESOLVED -> to == IncidentStatus.CLOSED;
			case CLOSED -> false;
		};
	}
}
