package com.group2.incident.incident.entity;

/**
 * Roles that users can hold during an incident (D39, FR-05.4).
 * Scribe is dropped: the timeline records events by itself.
 */
public enum IncidentRole {
	LEAD,
	COMMS,
	OPS
}
