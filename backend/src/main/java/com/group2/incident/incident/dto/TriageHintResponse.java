package com.group2.incident.incident.dto;

/**
 * Response body for POST /api/incidents/triage-hint (AI-4, Issue #247).
 * Fields match the AI service response contract exactly.
 */
public record TriageHintResponse(
        String severity,
        TriageHintServiceDto service,
        String reason
) {
}
