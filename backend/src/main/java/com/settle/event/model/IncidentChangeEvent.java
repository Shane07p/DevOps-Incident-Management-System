package com.settle.event.model;

/**
 * Published within a transaction when an incident is created, modified, or has timeline updates.
 */
public record IncidentChangeEvent(Long orgId, Long incidentId) {
}
