package com.group2.incident.audit.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEntryRepository auditEntryRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry record(AppUser actor, AuditAction action, AuditItemType itemType,
                             long itemId, String details) {
        AuditEntry entry = new AuditEntry(actor, action, itemType, itemId, details,
                clock.instant());
        return auditEntryRepository.save(entry);
    }
}
