package com.settle.incident.dto;

import com.settle.catalog.entity.Severity;

/**
 * Response body for POST /api/incidents/triage-hint (AI-4, Issue #247).
 * Fields match the AI service response contract exactly, strictly validated against Severity enum.
 */
public record TriageHintResponse(
        Severity severity,
        TriageHintServiceDto service,
        String reason
) {
}
