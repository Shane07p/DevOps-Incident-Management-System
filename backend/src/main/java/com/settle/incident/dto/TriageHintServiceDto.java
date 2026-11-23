package com.settle.incident.dto;

/**
 * Suggested service in a triage hint response (AI-4, Issue #247). Null when no service can be reliably identified.
 */
public record TriageHintServiceDto(Long id, String name) {
}
