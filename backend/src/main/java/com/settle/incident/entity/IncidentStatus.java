package com.settle.incident.entity;

/**
 * Status lifecycle of an incident (FR-06.1).
 */
public enum IncidentStatus {
	OPEN,
	ACKNOWLEDGED,
	INVESTIGATING,
	IDENTIFIED,
	MONITORING,
	RESOLVED,
	CLOSED
}
