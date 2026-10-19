package com.group2.incident.audit.service;

import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditEntryRepository auditEntryRepository;
    private final Clock clock;

    public AuditService(AuditEntryRepository auditEntryRepository, Clock clock) {
        this.auditEntryRepository = auditEntryRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry record(AppUser actor, String action, String itemType,
                             long itemId, String details) {
        AuditEntry entry = new AuditEntry(actor, action, itemType, itemId, details,
                clock.instant());
        return auditEntryRepository.save(entry);
    }
}
