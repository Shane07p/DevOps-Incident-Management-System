package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.group2.incident.catalog.repository.ServiceSlaTargetRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceCatalogServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-19T09:00:00Z");

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceSlaTargetRepository serviceSlaTargetRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private AuditService auditService;

    private Clock fixedClock;
    private ServiceCatalogService service;
    private AppUser owner;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ServiceCatalogService(serviceRepository, serviceSlaTargetRepository, appUserRepository, auditService, fixedClock);

        owner = new AppUser("alice", "Alice", "{noop}x", Role.ENGINEER, FIXED_NOW);
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
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
        CreateServiceRequest request = new CreateServiceRequest("Payments", "Handles charges", 1L, Criticality.HIGH, targets);

        when(serviceRepository.existsByNameIgnoreCase("Payments")).thenReturn(false);

        ServiceResponse response = service.create(request, 1L);

        assertThat(response.name()).isEqualTo("Payments");
        assertThat(response.criticality()).isEqualTo(Criticality.HIGH);
        assertThat(response.targets()).hasSize(4);

        verify(auditService).record(eq(owner), eq("SERVICE_CREATED"), eq("SERVICE"), anyLong(), anyString());
    }

    @Test
    void createServiceThrowsWhenSeverityTargetMissing() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.remove(Severity.SEV4);

        CreateServiceRequest request = new CreateServiceRequest("Search", "Desc", 1L, Criticality.LOW, targets);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Targets for SEV4 are required");
    }

    @Test
    void createServiceThrowsWhenResolutionShorterThanResponse() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        targets.put(Severity.SEV1, new SlaTargetDto(60, 30));

        CreateServiceRequest request = new CreateServiceRequest("Search", "Desc", 1L, Criticality.LOW, targets);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Resolution target can't be shorter than the response target");
    }

    @Test
    void createServiceThrowsOnDuplicateName() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        CreateServiceRequest request = new CreateServiceRequest("Payments", "Desc", 1L, Criticality.LOW, targets);

        when(serviceRepository.existsByNameIgnoreCase("Payments")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createServiceThrowsWhenOwnerNotFound() {
        Map<Severity, SlaTargetDto> targets = validTargets();
        CreateServiceRequest request = new CreateServiceRequest("NewService", "Desc", 999L, Criticality.LOW, targets);

        when(appUserRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Owner not found");
    }

    @Test
    void getUnknownServiceThrowsFieldValidationException() {
        when(serviceRepository.findWithDetailsById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Service not found");
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
