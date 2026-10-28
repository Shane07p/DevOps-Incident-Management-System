package com.group2.incident.incident.entity;

/**
 * Event types for timeline entries (FR-08).
 */
public enum TimelineEventType {
	INCIDENT_CREATED,
	ASSIGNED,
	ACKNOWLEDGED,
	STATUS_CHANGED,
	SEVERITY_CHANGED,
	REASSIGNED,
	ROLE_ASSIGNED,
	ALERT_ATTACHED,
	COMMENT,
	CORRECTION
}
