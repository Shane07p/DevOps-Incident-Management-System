package com.settle.audit.service;

import com.settle.audit.entity.AuditAction;
import com.settle.audit.entity.AuditEntry;
import com.settle.audit.entity.AuditItemType;
import com.settle.audit.repository.AuditEntryRepository;
import com.settle.auth.entity.AppUser;
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
