package com.group2.incident.audit.dto;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import java.time.Instant;

/**
 * Read-only view of one audit entry returned by GET /api/audit.
 * actorDisplayName is null for system-generated entries (FR-13.2).
 */
public record AuditEntryResponse(
        Long id,
        String actorDisplayName,
        AuditAction action,
        AuditItemType itemType,
        long itemId,
        String details,
        Instant recordedAt
) {}
