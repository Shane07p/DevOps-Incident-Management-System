package com.group2.incident.audit.dto;

import java.util.List;

/**
 * Paginated response for GET /api/audit.
 * Uses the project's existing pagination convention (content / page / size / totalElements).
 */
public record AuditPageResponse(
        List<AuditEntryResponse> content,
        int page,
        int size,
        long totalElements
) {}
