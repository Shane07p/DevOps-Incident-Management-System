package com.group2.incident.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * How long a service may take to respond to and to resolve an incident of one severity. Incidents
 * copy these numbers when they are created, so a later edit here never changes an existing incident.
 */
@Embeddable
public record SlaTarget(
		@Column(name = "response_minutes", nullable = false) int responseMinutes,
		@Column(name = "resolution_minutes", nullable = false) int resolutionMinutes) {
}
