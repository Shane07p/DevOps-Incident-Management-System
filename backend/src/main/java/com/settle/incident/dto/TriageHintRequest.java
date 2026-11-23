package com.settle.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/incidents/triage-hint (AI-4, Issue #247).
 * Title and description are validated here; the server re-checks everything.
 */
public record TriageHintRequest(
        @NotBlank(message = "Title must not be blank")
        @Size(max = 200, message = "Title must not exceed 200 characters")
        String title,

        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description
) {
}
