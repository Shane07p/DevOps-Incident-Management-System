package com.group2.incident.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@IntegrationTest
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEntryRepository auditEntryRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Test
    void recordSavesAllFieldsCorrectlyWithAnActor() {
        AppUser actor = appUserRepository.save(
                new AppUser("utsav", "Utsav B", "{noop}secret", Role.ADMIN, clock.instant()));

        AuditEntry saved = auditService.record(
                actor, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, 42L, "name: Payments");

        assertThat(saved.getId()).isNotNull();
        AuditEntry fetched = auditEntryRepository.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getActor().getId()).isEqualTo(actor.getId());
        assertThat(fetched.getAction()).isEqualTo(AuditAction.SERVICE_CREATED);
        assertThat(fetched.getItemType()).isEqualTo(AuditItemType.SERVICE);
        assertThat(fetched.getItemId()).isEqualTo(42L);
        assertThat(fetched.getDetails()).isEqualTo("name: Payments");
        assertThat(fetched.getRecordedAt()).isNotNull();
    }

    @Test
    void recordAcceptsNullActorForSystemActions() {
        AuditEntry saved = auditService.record(
                null, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "Bootstrap admin created");

        assertThat(saved.getId()).isNotNull();
        AuditEntry fetched = auditEntryRepository.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getActor()).isNull();
        assertThat(fetched.getAction()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(fetched.getItemType()).isEqualTo(AuditItemType.USER);
        assertThat(fetched.getItemId()).isEqualTo(1L);
        assertThat(fetched.getDetails()).isEqualTo("Bootstrap admin created");
        assertThat(fetched.getRecordedAt()).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rowIsSavedWithinCallerTransactionAndRolledBackIfCallerRollsBack() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);

        Long entryId;
        try {
            AuditEntry entry = auditService.record(
                    null, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "Transactional write");
            entryId = entry.getId();
            assertThat(entryId).isNotNull();
            assertThat(auditEntryRepository.findById(entryId)).isPresent();
        } finally {
            transactionManager.rollback(status);
        }

        assertThat(auditEntryRepository.findById(entryId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mandatoryPropagationThrowsWhenNoTransactionIsActive() {
        assertThatThrownBy(() -> auditService.record(
                null, AuditAction.USER_CREATED, AuditItemType.USER, 1L, "Action outside transaction"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
