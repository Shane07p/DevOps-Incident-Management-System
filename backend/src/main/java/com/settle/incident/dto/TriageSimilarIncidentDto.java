package com.settle.incident.dto;

/**
 * Past similar incident context passed to AI triage (AI-4, Issue #247).
 */
public record TriageSimilarIncidentDto(
        long id,
        String title,
        String severity,
        TriageHintServiceDto service
) {
}
