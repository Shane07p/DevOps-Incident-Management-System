package com.group2.incident.statuspage.entity;

/**
 * What customers are told about an incident (FR-21). Separate from the internal incident status:
 * Comms decides what to say and when. The incident leaves the page's active list at {@link #RESOLVED}.
 */
public enum PublicUpdateStatus {
	INVESTIGATING,
	IDENTIFIED,
	MONITORING,
	RESOLVED
}
