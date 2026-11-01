package com.group2.incident.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.group2.incident.catalog.dto.ServiceDependenciesResponse;
import com.group2.incident.catalog.dto.ServiceMapResponse;
import com.group2.incident.catalog.dto.ServiceResponse;
import com.group2.incident.catalog.dto.SlaTargetDto;
import com.group2.incident.catalog.dto.UpdateDependenciesRequest;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceDependency;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceDependencyRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
    private ServiceDependencyRepository serviceDependencyRepository;

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
        service = new ServiceCatalogService(serviceRepository, serviceDependencyRepository, appUserRepository, auditService, fixedClock);

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

        verify(auditService).record(eq(owner), eq(AuditAction.SERVICE_CREATED), eq(AuditItemType.SERVICE), anyLong(), anyString());
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
    void getUnknownServiceThrowsNotFoundException() {
        when(serviceRepository.findWithDetailsById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service not found");
    }

    @Test
    void replaceDependenciesThrowsWhenServiceNotFound() {
        when(serviceRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceDependencies(999L, new UpdateDependenciesRequest(List.of(1L)), 1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service 999 not found");
    }

    @Test
    void replaceDependenciesThrowsOnSelfDependency() {
        ServiceEntity svc = new ServiceEntity("Svc", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        when(serviceRepository.findById(10L)).thenReturn(Optional.of(svc));

        assertThatThrownBy(() -> service.replaceDependencies(10L, new UpdateDependenciesRequest(List.of(10L)), 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("A service cannot depend on itself");
    }

    @Test
    void replaceDependenciesThrowsWhenReferencedServiceNotFound() {
        ServiceEntity svc = new ServiceEntity("Svc", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        when(serviceRepository.findById(10L)).thenReturn(Optional.of(svc));
        when(serviceRepository.findAllById(List.of(20L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.replaceDependencies(10L, new UpdateDependenciesRequest(List.of(20L)), 1L))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("Referenced service does not exist: 20");
    }

    @Test
    void replaceDependenciesReturnsExistingWhenUnchanged() {
        ServiceEntity svc = new ServiceEntity("Svc", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        when(serviceRepository.findById(10L)).thenReturn(Optional.of(svc));
        when(serviceDependencyRepository.findDependsOnIdsByServiceId(10L)).thenReturn(List.of(20L));

        ServiceEntity ref = new ServiceEntity("Ref", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        try {
            var f = ServiceEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(ref, 20L);
        } catch (Exception ignored) {}
        when(serviceRepository.findAllById(List.of(20L))).thenReturn(List.of(ref));

        ServiceDependenciesResponse res = service.replaceDependencies(10L, new UpdateDependenciesRequest(List.of(20L)), 1L);

        assertThat(res.serviceId()).isEqualTo(10L);
        assertThat(res.dependsOn()).containsExactly(20L);
        verify(serviceDependencyRepository, never()).deleteByServiceId(anyLong());
        verify(auditService, never()).record(any(), any(), any(), anyLong(), anyString());
    }

    @Test
    void replaceDependenciesUpdatesAndRecordsAuditWhenChanged() {
        ServiceEntity svc = new ServiceEntity("Svc", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        when(serviceRepository.findById(10L)).thenReturn(Optional.of(svc));
        when(serviceDependencyRepository.findDependsOnIdsByServiceId(10L)).thenReturn(List.of(20L));

        ServiceEntity ref = new ServiceEntity("Ref30", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        try {
            var f = ServiceEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(ref, 30L);
        } catch (Exception ignored) {}
        when(serviceRepository.findAllById(List.of(30L))).thenReturn(List.of(ref));

        ServiceDependenciesResponse res = service.replaceDependencies(10L, new UpdateDependenciesRequest(List.of(30L)), 1L);

        assertThat(res.serviceId()).isEqualTo(10L);
        assertThat(res.dependsOn()).containsExactly(30L);
        verify(serviceDependencyRepository).deleteByServiceId(10L);
        verify(serviceDependencyRepository).saveAllAndFlush(any());
        verify(auditService).record(eq(owner), eq(AuditAction.SERVICE_DEPENDENCIES_UPDATED), eq(AuditItemType.SERVICE), eq(10L), anyString());
    }

    @Test
    void getBlastRadiusThrowsWhenServiceNotFound() {
        when(serviceRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.getBlastRadius(999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service 999 not found");
    }

    @Test
    void getBlastRadiusTraversesGraphSafely() {
        when(serviceRepository.existsById(10L)).thenReturn(true);
        // Dep: 20 depends on 10, 30 depends on 20
        when(serviceDependencyRepository.findAll()).thenReturn(List.of(
                new ServiceDependency(20L, 10L),
                new ServiceDependency(30L, 20L)
        ));

        ServiceEntity s20 = new ServiceEntity("Svc20", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        ServiceEntity s30 = new ServiceEntity("Svc30", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        try {
            var f = ServiceEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s20, 20L);
            f.set(s30, 30L);
        } catch (Exception ignored) {}

        when(serviceRepository.findAllById(any())).thenReturn(List.of(s20, s30));

        List<ServiceResponse> blast = service.getBlastRadius(10L);
        assertThat(blast).hasSize(2);
        assertThat(blast.stream().map(ServiceResponse::name).toList()).containsExactly("Svc20", "Svc30");
    }

    @Test
    void getServiceMapReturnsNodesAndEdges() {
        ServiceEntity s1 = new ServiceEntity("S1", "Desc", owner, Criticality.HIGH, FIXED_NOW);
        ServiceEntity s2 = new ServiceEntity("S2", "Desc", owner, Criticality.CRITICAL, FIXED_NOW);
        try {
            var f = ServiceEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s1, 1L);
            f.set(s2, 2L);
        } catch (Exception ignored) {}

        when(serviceRepository.findAll()).thenReturn(List.of(s1, s2));
        when(serviceDependencyRepository.findAll()).thenReturn(List.of(new ServiceDependency(1L, 2L)));

        ServiceMapResponse map = service.getServiceMap();
        assertThat(map.nodes()).hasSize(2);
        assertThat(map.nodes().get(0).health()).isEqualTo("UNKNOWN");
        assertThat(map.edges()).hasSize(1);
        assertThat(map.edges().get(0).from()).isEqualTo(1L);
        assertThat(map.edges().get(0).to()).isEqualTo(2L);
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
