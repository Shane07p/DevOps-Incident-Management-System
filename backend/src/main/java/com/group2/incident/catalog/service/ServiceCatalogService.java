package com.group2.incident.catalog.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.CreateServiceRequest;
import com.group2.incident.catalog.dto.ServiceDependenciesResponse;
import com.group2.incident.catalog.dto.ServiceMapEdgeDto;
import com.group2.incident.catalog.dto.ServiceMapNodeDto;
import com.group2.incident.catalog.dto.ServiceMapResponse;
import com.group2.incident.catalog.dto.ServiceResponse;
import com.group2.incident.catalog.dto.SlaTargetDto;
import com.group2.incident.catalog.dto.UpdateDependenciesRequest;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceDependency;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.ServiceDependencyRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceCatalogService {

    private static final Map<Severity, Integer> DEFAULT_RESPONSE_MINUTES = Map.of(
            Severity.SEV1, 15,
            Severity.SEV2, 60,
            Severity.SEV3, 240,
            Severity.SEV4, 1440
    );

    private final ServiceRepository serviceRepository;
    private final ServiceDependencyRepository serviceDependencyRepository;
    private final AppUserRepository appUserRepository;
    private final AuditService auditService;
    private final Clock clock;

    public List<ServiceResponse> list() {
        return serviceRepository.findAllByOrderByNameAsc().stream()
                .map(ServiceResponse::from)
                .toList();
    }

    public ServiceResponse get(Long id) {
        return ServiceResponse.from(load(id));
    }

    public Map<Severity, SlaTargetDto> defaultTargets() {
        Map<Severity, SlaTargetDto> defaults = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            defaults.put(severity, new SlaTargetDto(DEFAULT_RESPONSE_MINUTES.get(severity), null));
        }
        return defaults;
    }

    public ServiceEntity requireForIncident(Long serviceId) {
        if (serviceId == null) {
            throw new FieldValidationException("serviceId", "Service is required");
        }
        return serviceRepository.findWithDetailsById(serviceId)
                .orElseThrow(() -> new FieldValidationException("serviceId", "Service not found"));
    }

    @Transactional
    public ServiceResponse create(CreateServiceRequest request, Long actorId) {
        Map<Severity, SlaTargetDto> resolvedTargets = validateAndResolveTargets(request.targets());
        AppUser owner = resolveOwner(request.ownerId());
        String name = request.name().strip();

        if (serviceRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A service named " + name + " already exists");
        }

        ServiceEntity service = new ServiceEntity(name, clean(request.description()), owner, request.criticality(), clock.instant());
        for (Map.Entry<Severity, SlaTargetDto> entry : resolvedTargets.entrySet()) {
            service.putTarget(entry.getKey(), entry.getValue().responseMinutes(), entry.getValue().resolutionMinutes());
        }

        ServiceEntity saved = saveService(service, name);
        AppUser actor = actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
        auditService.record(actor, AuditAction.SERVICE_CREATED, AuditItemType.SERVICE, saved.getId(), describe(saved));

        return ServiceResponse.from(saved);
    }

    @Transactional
    public ServiceResponse update(Long id, CreateServiceRequest request, Long actorId) {
        ServiceEntity service = load(id);
        Map<Severity, SlaTargetDto> resolvedTargets = validateAndResolveTargets(request.targets());

        AppUser owner = service.getOwner().getId().equals(request.ownerId())
                ? service.getOwner()
                : resolveOwner(request.ownerId());

        String name = request.name().strip();
        if (serviceRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A service named " + name + " already exists");
        }

        String description = clean(request.description());
        List<String> changes = detectChanges(service, name, description, owner, request.criticality(), resolvedTargets);
        if (changes.isEmpty()) {
            return ServiceResponse.from(service);
        }

        service.update(name, description, owner, request.criticality(), clock.instant());
        for (Map.Entry<Severity, SlaTargetDto> entry : resolvedTargets.entrySet()) {
            service.putTarget(entry.getKey(), entry.getValue().responseMinutes(), entry.getValue().resolutionMinutes());
        }

        ServiceEntity saved = saveService(service, name);
        AppUser actor = actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
        auditService.record(actor, AuditAction.SERVICE_UPDATED, AuditItemType.SERVICE, id, String.join("; ", changes));

        return ServiceResponse.from(saved);
    }

    @Transactional
    public ServiceDependenciesResponse replaceDependencies(Long serviceId, UpdateDependenciesRequest request, Long actorId) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));

        List<Long> requested = request.dependsOn() != null ? request.dependsOn() : List.of();
        List<Long> distinctDependsOn = requested.stream().distinct().toList();

        if (distinctDependsOn.contains(serviceId)) {
            throw new FieldValidationException("dependsOn", "A service cannot depend on itself");
        }

        if (!distinctDependsOn.isEmpty()) {
            List<ServiceEntity> referenced = serviceRepository.findAllById(distinctDependsOn);
            if (referenced.size() != distinctDependsOn.size()) {
                Set<Long> foundIds = referenced.stream().map(ServiceEntity::getId).collect(Collectors.toSet());
                Long missingId = distinctDependsOn.stream().filter(id -> !foundIds.contains(id)).findFirst().orElse(null);
                throw new FieldValidationException("dependsOn", "Referenced service does not exist: " + missingId);
            }
        }

        List<Long> currentDependsOn = serviceDependencyRepository.findDependsOnIdsByServiceId(serviceId);
        Set<Long> currentSet = new HashSet<>(currentDependsOn);
        Set<Long> newSet = new HashSet<>(distinctDependsOn);

        if (currentSet.equals(newSet)) {
            return new ServiceDependenciesResponse(serviceId, distinctDependsOn);
        }

        serviceDependencyRepository.deleteByServiceId(serviceId);
        serviceDependencyRepository.flush();
        List<ServiceDependency> newEntities = distinctDependsOn.stream()
                .map(depId -> new ServiceDependency(serviceId, depId))
                .toList();
        serviceDependencyRepository.saveAllAndFlush(newEntities);

        AppUser actor = actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
        String details = "dependencies: " + currentDependsOn + " -> " + distinctDependsOn;
        auditService.record(actor, AuditAction.SERVICE_DEPENDENCIES_UPDATED, AuditItemType.SERVICE, serviceId, details);

        return new ServiceDependenciesResponse(serviceId, distinctDependsOn);
    }

    public ServiceMapResponse getServiceMap() {
        List<ServiceEntity> allServices = serviceRepository.findAll();
        List<ServiceMapNodeDto> nodes = allServices.stream()
                .map(s -> new ServiceMapNodeDto(s.getId(), s.getName(), s.getCriticality(), "UNKNOWN"))
                .sorted(Comparator.comparing(ServiceMapNodeDto::id))
                .toList();

        List<ServiceDependency> allDependencies = serviceDependencyRepository.findAll();
        List<ServiceMapEdgeDto> edges = allDependencies.stream()
                .map(d -> new ServiceMapEdgeDto(d.getServiceId(), d.getDependsOnId()))
                .sorted(Comparator.comparing(ServiceMapEdgeDto::from).thenComparing(ServiceMapEdgeDto::to))
                .toList();

        return new ServiceMapResponse(nodes, edges);
    }

    public List<ServiceResponse> getBlastRadius(Long serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new NotFoundException("Service " + serviceId + " not found");
        }

        List<ServiceDependency> allDependencies = serviceDependencyRepository.findAll();
        Map<Long, List<Long>> dependentsMap = new HashMap<>();
        for (ServiceDependency dep : allDependencies) {
            dependentsMap.computeIfAbsent(dep.getDependsOnId(), k -> new ArrayList<>()).add(dep.getServiceId());
        }

        Set<Long> visited = new LinkedHashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(serviceId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<Long> dependents = dependentsMap.getOrDefault(current, List.of());
            for (Long dependentId : dependents) {
                if (dependentId.equals(serviceId)) {
                    continue;
                }
                if (visited.add(dependentId)) {
                    queue.add(dependentId);
                }
            }
        }

        if (visited.isEmpty()) {
            return List.of();
        }

        return serviceRepository.findAllById(visited).stream()
                .map(ServiceResponse::from)
                .sorted(Comparator.comparing(ServiceResponse::name))
                .toList();
    }

    private ServiceEntity saveService(ServiceEntity service, String name) {
        try {
            return serviceRepository.saveAndFlush(service);
        } catch (DataIntegrityViolationException ex) {
            String msg = ex.getMessage();
            Throwable cause = ex.getRootCause();
            String causeMsg = cause != null ? cause.getMessage() : "";
            String combined = ((msg != null ? msg : "") + " " + causeMsg).toLowerCase();
            if (combined.contains("service_name") || combined.contains("service_name_key") || combined.contains("service_name_lower_key")) {
                throw new ConflictException("A service named " + name + " already exists");
            }
            throw ex;
        }
    }

    private ServiceEntity load(Long id) {
        return serviceRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Service not found"));
    }

    private AppUser resolveOwner(Long ownerId) {
        return appUserRepository.findById(ownerId)
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new FieldValidationException("ownerId", "Owner not found"));
    }

    private Map<Severity, SlaTargetDto> validateAndResolveTargets(Map<Severity, SlaTargetDto> targets) {
        Map<Severity, SlaTargetDto> resolved = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            String field = "targets." + severity.name();
            SlaTargetDto target = targets != null ? targets.get(severity) : null;
            if (target == null) {
                throw new FieldValidationException(field, "Targets for " + severity.name() + " are required");
            }

            int response = target.responseMinutes() == null
                    ? DEFAULT_RESPONSE_MINUTES.get(severity)
                    : checkRange(field + ".responseMinutes", target.responseMinutes());

            if (target.resolutionMinutes() == null) {
                throw new FieldValidationException(field + ".resolutionMinutes", "Resolution target is required");
            }

            int resolution = checkRange(field + ".resolutionMinutes", target.resolutionMinutes());
            if (resolution < response) {
                throw new FieldValidationException(field + ".resolutionMinutes",
                        "Resolution target can't be shorter than the response target");
            }

            resolved.put(severity, new SlaTargetDto(response, resolution));
        }
        return resolved;
    }

    private static int checkRange(String field, int minutes) {
        if (minutes < 1) {
            throw new FieldValidationException(field, "Must be at least 1 minute");
        }
        return minutes;
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String describe(ServiceEntity service) {
        StringBuilder details = new StringBuilder()
                .append(service.getName()).append(" (").append(service.getCriticality())
                .append("), owner ").append(service.getOwner().getDisplayName()).append("; targets");
        for (Map.Entry<Severity, SlaTarget> entry : service.getTargets().entrySet()) {
            details.append(' ').append(entry.getKey()).append(' ')
                    .append(entry.getValue().getResponseMinutes()).append('/').append(entry.getValue().getResolutionMinutes()).append(" min");
        }
        return details.toString();
    }

    private static List<String> detectChanges(ServiceEntity before, String name, String description,
                                              AppUser owner, Criticality criticality,
                                              Map<Severity, SlaTargetDto> targets) {
        List<String> changes = new ArrayList<>();
        if (!before.getName().equals(name)) {
            changes.add("name: " + before.getName() + " → " + name);
        }
        if (!Objects.equals(before.getDescription(), description)) {
            changes.add("description changed");
        }
        if (!before.getOwner().getId().equals(owner.getId())) {
            changes.add("owner: " + before.getOwner().getDisplayName() + " → " + owner.getDisplayName());
        }
        if (before.getCriticality() != criticality) {
            changes.add("criticality: " + before.getCriticality() + " → " + criticality);
        }

        Map<Severity, SlaTarget> currentMap = before.getTargets();
        for (Severity severity : Severity.values()) {
            SlaTarget old = currentMap.get(severity);
            SlaTargetDto now = targets.get(severity);
            if (old != null && now != null) {
                if (old.getResponseMinutes() != now.responseMinutes()) {
                    changes.add(severity + " response: " + old.getResponseMinutes() + " → " + now.responseMinutes() + " min");
                }
                if (old.getResolutionMinutes() != now.resolutionMinutes()) {
                    changes.add(severity + " resolution: " + old.getResolutionMinutes() + " → " + now.resolutionMinutes() + " min");
                }
            }
        }
        return changes;
    }
}
