package com.group2.incident.audit.controller;

import com.group2.incident.audit.dto.AuditPageResponse;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditQueryService;
import com.group2.incident.common.error.FieldValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit log endpoint (FR-13, Issue #153). Admin access only.
 *
 * <p>The endpoint is read-only and never mutates audit entries (FR-13.3 / NFR-08.2).
 * Authorization is enforced in SecurityConfig: {@code /api/audit/**} requires ADMIN.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Audit log — Admin access only. Read-only; no entry is created, edited, or deleted by this endpoint.")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService auditQueryService;

    /**
     * Returns audit entries newest first, 50 per page.
     *
     * <p>All filters are combined with AND semantics. Omitting a filter means no restriction on
     * that dimension. The {@code from} bound is inclusive; {@code to} is exclusive.
     */
    @GetMapping
    @Operation(
            summary = "List audit entries (Admin only)",
            description = """
                    Returns audit entries sorted by recordedAt DESC, then id DESC (for stable \
                    pagination at equal timestamps). Page size is fixed at 50. \
                    All query parameters are optional and combined with AND semantics. \
                    actorDisplayName is null for system-generated entries.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of audit entries"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameter (negative page, unparseable timestamp, or from >= to)",
                    content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError"))),
            @ApiResponse(responseCode = "401", description = "No or invalid token"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN")
    })
    public AuditPageResponse listAuditEntries(
            @Parameter(description = "Zero-based page number (default 0, must be >= 0)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Exact item-type filter (e.g. USER, SERVICE, INCIDENT, TOOL)")
            @RequestParam(required = false) String itemType,

            @Parameter(description = "Item-ID filter")
            @RequestParam(required = false) Long itemId,

            @Parameter(description = "Actor user ID filter (filters by the actor's database ID)")
            @RequestParam(required = false) Long actorId,

            @Parameter(description = "Inclusive lower timestamp bound (ISO-8601 UTC, e.g. 2026-10-01T00:00:00Z)")
            @RequestParam(required = false) String from,

            @Parameter(description = "Exclusive upper timestamp bound (ISO-8601 UTC, e.g. 2026-11-01T00:00:00Z)")
            @RequestParam(required = false) String to
    ) {
        AuditItemType parsedItemType = parseItemType(itemType);
        Instant parsedFrom = parseInstant(from, "from");
        Instant parsedTo = parseInstant(to, "to");

        return auditQueryService.listAuditEntries(page, parsedItemType, itemId, actorId, parsedFrom, parsedTo);
    }

    // ── Parameter parsers ────────────────────────────────────────────────────

    private static AuditItemType parseItemType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditItemType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("itemType",
                    "Unknown item type '" + raw + "'. Valid values: USER, SERVICE, INCIDENT, TOOL");
        }
    }

    private static Instant parseInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new FieldValidationException(fieldName,
                    "'" + fieldName + "' must be an ISO-8601 UTC timestamp (e.g. 2026-10-01T00:00:00Z)");
        }
    }
}
