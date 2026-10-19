package com.group2.incident.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AuditServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-19T09:00:00Z");

    @Mock
    private AuditEntryRepository auditEntryRepository;

    private Clock fixedClock;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        auditService = new AuditService(auditEntryRepository, fixedClock);

        when(auditEntryRepository.save(ArgumentMatchers.any(AuditEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordSavesAllFieldsCorrectlyWithAnActor() {
        AppUser actor = new AppUser("utsav", "Utsav B", "{noop}x", Role.ADMIN, FIXED_NOW);

        auditService.record(actor, "SERVICE_CREATED", "SERVICE", 42L, "name: Payments");

        ArgumentCaptor<AuditEntry> captor = forClass(AuditEntry.class);
        verify(auditEntryRepository).save(captor.capture());
        AuditEntry saved = captor.getValue();

        assertThat(saved.getActor()).isSameAs(actor);
        assertThat(saved.getAction()).isEqualTo("SERVICE_CREATED");
        assertThat(saved.getItemType()).isEqualTo("SERVICE");
        assertThat(saved.getItemId()).isEqualTo(42L);
        assertThat(saved.getDetails()).isEqualTo("name: Payments");
        assertThat(saved.getRecordedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void recordAcceptsNullActorForSystemActions() {
        auditService.record(null, "USER_CREATED", "USER", 1L, "Bootstrap admin created");

        ArgumentCaptor<AuditEntry> captor = forClass(AuditEntry.class);
        verify(auditEntryRepository).save(captor.capture());

        assertThat(captor.getValue().getActor()).isNull();
    }

    @Test
    void recordedAtMatchesTheInjectedFixedClock() {
        auditService.record(null, "SERVICE_UPDATED", "SERVICE", 7L, "criticality: MEDIUM → HIGH");

        ArgumentCaptor<AuditEntry> captor = forClass(AuditEntry.class);
        verify(auditEntryRepository).save(captor.capture());

        assertThat(captor.getValue().getRecordedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void recordMethodIsAnnotatedWithMandatoryPropagation() throws NoSuchMethodException {
        Method method = AuditService.class.getMethod(
                "record", AppUser.class, String.class, String.class, long.class, String.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void mandatoryPropagationThrowsWhenNoTransactionIsActive() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertThatThrownBy(() -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalTransactionStateException(
                        "No existing transaction found for transaction marked with propagation 'mandatory'");
            }
        }).isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("mandatory");
    }
}
