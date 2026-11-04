package com.group2.incident.audit.service;

import com.group2.incident.audit.dto.AuditEntryResponse;
import com.group2.incident.audit.dto.AuditPageResponse;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.common.error.FieldValidationException;
import jakarta.persistence.criteria.JoinType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query service for the audit log (FR-13, Issue #153).
 * The write path stays in AuditService. No interface is added because there is only one implementation.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    public static final int PAGE_SIZE = 50;

    private final AuditEntryRepository auditEntryRepository;

    /**
     * Returns a paginated, filtered view of the audit log, newest first.
     *
     * @param page       zero-based page number (must be >= 0)
     * @param itemType   optional exact item-type filter
     * @param itemId     optional item-ID filter
     * @param actorId    optional actor-ID filter (filters by the actor's database ID)
     * @param from       optional inclusive lower bound (must be before {@code to} when both given)
     * @param to         optional exclusive upper bound
     */
    @Transactional(readOnly = true)
    public AuditPageResponse listAuditEntries(
            int page,
            AuditItemType itemType,
            Long itemId,
            Long actorId,
            Instant from,
            Instant to
    ) {
        if (page < 0) {
            throw new FieldValidationException("page", "must not be negative");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new FieldValidationException("from", "'from' must be before 'to'");
        }

        Specification<AuditEntry> spec = buildSpec(itemType, itemId, actorId, from, to);

        // Primary sort: recordedAt DESC; secondary: id DESC for stable pagination at equal timestamps.
        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "recordedAt").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<AuditEntry> result = auditEntryRepository.findAll(spec, pageable);

        return new AuditPageResponse(
                result.getContent().stream().map(AuditQueryService::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    // ── Specification builder ────────────────────────────────────────────────

    /**
     * Builds a Specification from the supplied filters (all optional).
     * Combined with AND semantics. Returns a match-all spec when no filters are supplied.
     * Avoids Specification.where(null) which is ambiguous in Spring Data 4.
     */
    private static Specification<AuditEntry> buildSpec(
            AuditItemType itemType,
            Long itemId,
            Long actorId,
            Instant from,
            Instant to
    ) {
        List<Specification<AuditEntry>> parts = new ArrayList<>();

        if (itemType != null) {
            final AuditItemType ft = itemType;
            parts.add((root, query, cb) -> cb.equal(root.get("itemType"), ft));
        }
        if (itemId != null) {
            final Long fi = itemId;
            parts.add((root, query, cb) -> cb.equal(root.get("itemId"), fi));
        }
        if (actorId != null) {
            final Long fa = actorId;
            // INNER JOIN: entries with a null actor are excluded when actorId is provided (correct AND semantics).
            parts.add((root, query, cb) -> {
                var actor = root.join("actor", JoinType.INNER);
                return cb.equal(actor.get("id"), fa);
            });
        }
        if (from != null) {
            final Instant ff = from;
            parts.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("recordedAt"), ff));
        }
        if (to != null) {
            final Instant ft = to;
            parts.add((root, query, cb) -> cb.lessThan(root.get("recordedAt"), ft));
        }

        if (parts.isEmpty()) {
            return (root, query, cb) -> cb.conjunction(); // match all
        }

        Specification<AuditEntry> result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = result.and(parts.get(i));
        }
        return result;
    }

    // ── Mapper ───────────────────────────────────────────────────────────────

    private static AuditEntryResponse toResponse(AuditEntry entry) {
        String actorDisplayName = entry.getActor() != null ? entry.getActor().getDisplayName() : null;
        return new AuditEntryResponse(
                entry.getId(),
                actorDisplayName,
                entry.getAction(),
                entry.getItemType(),
                entry.getItemId(),
                entry.getDetails(),
                entry.getRecordedAt()
        );
    }
}
