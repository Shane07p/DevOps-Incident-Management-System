package com.settle.audit.dto;

import com.settle.audit.entity.AuditAction;
import com.settle.audit.entity.AuditItemType;
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
