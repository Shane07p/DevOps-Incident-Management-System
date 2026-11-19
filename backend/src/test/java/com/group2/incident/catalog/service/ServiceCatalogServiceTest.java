package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.CreateServiceRequest;
import com.group2.incident.catalog.dto.ServiceResponse;
import com.group2.incident.catalog.dto.SlaTargetDto;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.org.service.OrgMembers;
import com.group2.incident.support.Members;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceCatalogServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-19T09:00:00Z");

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private OrgMembers orgMembers;

    @Mock
    private AuditService auditService;

    private Clock fixedClock;
    private ServiceCatalogService service;
    private AppUser owner;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ServiceCatalogService(serviceRepository, appUserRepository, orgMembers, auditService, fixedClock);

        owner = new AppUser("alice@test.local", "Alice", "{noop}x", FIXED_NOW);
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(orgMembers.find(1L)).thenReturn(Optional.of(Members.of(owner, Role.ENGINEER)));
        when(serviceRepository.saveAndFlush(any(ServiceEntity.class))).thenAnswer(inv -> {
            ServiceEntity entity = inv.getArgument(0);
            try {
                java.lang.reflect.Field field = ServiceEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(entity, 42L);
            } catch (Exception ignored) {}
            return entity;
        });
    }

    @Test
    void createServiceSuccessWithAudit() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        CreateServiceRequest request = new CreateServiceRequest("Payments", "Handles charges", 1L, Criticality.HIGH, targets, null, null, null);

        when(serviceRepository.existsByNameIgnoreCase("Payments")).thenReturn(false);

        ServiceResponse response = service.create(request, 1L);

        assertThat(response.name()).isEqualTo("Payments");
        assertThat(response.criticality()).isEqualTo(Criticality.HIGH);
        assertThat(response.targets()).hasSize(4);

        verify(auditService).record(eq(owner), eq(AuditAction.SERVICE_CREATED), eq(AuditItemType.SERVICE), anyLong(), anyString());
    }

    @Test
    void createServiceThrowsWhenSeverityTargetMissing() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.remove(Severity.SEV4);

        CreateServiceRequest request = new CreateServiceRequest("Search", "Desc", 1L, Criticality.LOW, targets, null, null, null);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Targets for SEV4 are required");
    }

    @Test
    void createServiceThrowsWhenResolutionShorterThanResponse() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.put(Severity.SEV1, new SlaTargetDto(60, 30));

        CreateServiceRequest request = new CreateServiceRequest("Search", "Desc", 1L, Criticality.LOW, targets, null, null, null);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Resolution target can't be shorter than the response target");
    }

    @Test
    void createServiceThrowsOnDuplicateName() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        CreateServiceRequest request = new CreateServiceRequest("Payments", "Desc", 1L, Criticality.LOW, targets, null, null, null);

        when(serviceRepository.existsByNameIgnoreCase("Payments")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createServiceThrowsWhenOwnerNotFound() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        CreateServiceRequest request = new CreateServiceRequest("NewService", "Desc", 999L, Criticality.LOW, targets, null, null, null);

        when(orgMembers.find(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Owner not found");
    }

    @Test
    void getUnknownServiceThrowsNotFoundException() {
        when(serviceRepository.findWithDetailsById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service not found");
    }

    @Test
    void anIncidentNeedsAServiceThatExists() {
        ServiceEntity payments = new ServiceEntity("Payments", null, owner, Criticality.HIGH, FIXED_NOW);
        when(serviceRepository.findWithDetailsById(42L)).thenReturn(Optional.of(payments));
        when(serviceRepository.findWithDetailsById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireForIncident(null))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Service is required");
        assertThatThrownBy(() -> service.requireForIncident(999L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Service not found");
        assertThat(service.requireForIncident(42L)).isSameAs(payments);
    }

    // No actor (a system call) still leaves an audit entry; a missing response target takes the default.
    @Test
    void createWithoutAnActorStillAuditsAndDescribesTheStatusPageName() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.put(Severity.SEV2, new SlaTargetDto(null, 480));
        CreateServiceRequest request = new CreateServiceRequest("Payments", "  ", 1L, Criticality.HIGH, targets, true, " Card payments ", null);

        ServiceResponse response = service.create(request, null);

        assertThat(response.targets().get(Severity.SEV2).responseMinutes()).isEqualTo(60);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(isNull(), eq(AuditAction.SERVICE_CREATED), eq(AuditItemType.SERVICE), anyLong(), details.capture());
        assertThat(details.getValue()).contains("SEV2 60/480 min", "on the status page as Card payments");
    }

    @Test
    void createRefusesMissingTargetsAResolutionLeftOutAndTargetsUnderAMinute() {
        Map<Severity, SlaTargetDto> noResolution = validTargets();
        noResolution.put(Severity.SEV3, new SlaTargetDto(240, null));
        Map<Severity, SlaTargetDto> zeroResponse = validTargets();
        zeroResponse.put(Severity.SEV1, new SlaTargetDto(0, 240));

        assertThatThrownBy(() -> service.create(request("Search", null), 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Targets for SEV1 are required");
        assertThatThrownBy(() -> service.create(request("Search", noResolution), 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Resolution target is required");
        assertThatThrownBy(() -> service.create(request("Search", zeroResponse), 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Must be at least 1 minute");
    }

    @Test
    void showingAServiceOnTheStatusPageNeedsAPublicName() {
        CreateServiceRequest request = new CreateServiceRequest("Payments", null, 1L, Criticality.HIGH, validTargets(), true, "  ", null);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("public name is required");
    }

    // Two requests can both pass the name check; the database's unique key then decides, and the loser gets 409.
    @Test
    void aNameTakenAtTheDatabaseGivesAConflictAndOtherDatabaseErrorsPassThrough() {
        when(serviceRepository.saveAndFlush(any(ServiceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("duplicate key value violates unique constraint \"service_name_lower_key\"")))
                .thenThrow(new DataIntegrityViolationException("value too long for column"));

        assertThatThrownBy(() -> service.create(request("Payments", validTargets()), 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("A service named Payments already exists");
        assertThatThrownBy(() -> service.create(request("Payments", validTargets()), 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUpdateThatChangesNothingSavesAndAuditsNothing() {
        ServiceEntity existing = existing();
        CreateServiceRequest same = new CreateServiceRequest("Payments", "Handles charges", 1L, Criticality.HIGH, validTargets(), null, null, null);

        ServiceResponse response = service.update(42L, same, 1L);

        assertThat(response.name()).isEqualTo("Payments");
        verify(serviceRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), anyLong(), any());
        assertThat(existing.getUpdatedAt()).isEqualTo(FIXED_NOW.minusSeconds(3600));
    }

    @Test
    void anUpdateCannotTakeAnotherServicesName() {
        existing();
        when(serviceRepository.existsByNameIgnoreCaseAndIdNot("Search", 42L)).thenReturn(true);
        CreateServiceRequest rename = new CreateServiceRequest("Search", "Handles charges", 1L, Criticality.HIGH, validTargets(), null, null, null);

        assertThatThrownBy(() -> service.update(42L, rename, 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("A service named Search already exists");
    }

    @Test
    void anUpdateAuditsEachChange() {
        existing();
        AppUser bob = new AppUser("bob@test.local", "Bob", "{noop}x", FIXED_NOW);
        ReflectionTestUtils.setField(bob, "id", 2L);
        when(orgMembers.find(2L)).thenReturn(Optional.of(Members.of(bob, Role.ENGINEER)));
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.put(Severity.SEV1, new SlaTargetDto(10, 120));
        CreateServiceRequest request = new CreateServiceRequest("Card payments", "Charges and refunds", 2L, Criticality.CRITICAL,
                targets, true, "Payments", "payments-api");

        service.update(42L, request, null);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(isNull(), eq(AuditAction.SERVICE_UPDATED), eq(AuditItemType.SERVICE), eq(42L), details.capture());
        assertThat(details.getValue()).contains(
                "name: Payments → Card payments", "description changed", "owner: Alice → Bob", "criticality: HIGH → CRITICAL",
                "SEV1 response: 15 → 10 min", "SEV1 resolution: 240 → 120 min", "shown on the status page",
                "public name: none → Payments", "telemetry label: payments-api");
    }

    // A blank label puts the default back; taking a service off the status page clears its public name.
    @Test
    void anUpdateCanClearTheTelemetryLabelAndHideTheService() {
        ServiceEntity existing = existing();
        existing.publishAs(true, "Payments", FIXED_NOW);
        existing.labelTelemetryAs("payments-api");
        CreateServiceRequest request = new CreateServiceRequest("Payments", "Handles charges", 1L, Criticality.HIGH,
                validTargets(), false, "", "  ");

        service.update(42L, request, 1L);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(owner), eq(AuditAction.SERVICE_UPDATED), eq(AuditItemType.SERVICE), eq(42L), details.capture());
        assertThat(details.getValue()).contains("hidden from the status page", "public name: Payments → none", "telemetry label: default");
        assertThat(existing.getTelemetryLabel()).isNull();
        assertThat(existing.isPublicVisible()).isFalse();
    }

    // Payments, owned by Alice, with all four targets; saved an hour ago.
    private ServiceEntity existing() {
        ReflectionTestUtils.setField(owner, "id", 1L);
        ServiceEntity existing = new ServiceEntity("Payments", "Handles charges", owner, Criticality.HIGH, FIXED_NOW.minusSeconds(3600));
        validTargets().forEach((severity, target) -> existing.putTarget(severity, target.responseMinutes(), target.resolutionMinutes()));
        ReflectionTestUtils.setField(existing, "id", 42L);
        when(serviceRepository.findWithDetailsById(42L)).thenReturn(Optional.of(existing));
        return existing;
    }

    private static CreateServiceRequest request(String name, Map<Severity, SlaTargetDto> targets) {
        return new CreateServiceRequest(name, null, 1L, Criticality.LOW, targets, null, null, null);
    }

    private Map<Severity, SlaTargetDto> validTargets() {
        Map<Severity, SlaTargetDto> targets = new HashMap<>();
        targets.put(Severity.SEV1, new SlaTargetDto(15, 240));
        targets.put(Severity.SEV2, new SlaTargetDto(60, 480));
        targets.put(Severity.SEV3, new SlaTargetDto(240, 2880));
        targets.put(Severity.SEV4, new SlaTargetDto(1440, 10080));
        return targets;
    }
}
