package com.settle.event.dto;

/**
 * Payload sent to SSE subscribers on an `incident-changed` event.
 */
public record IncidentChangedNotification(Long id) {
}
