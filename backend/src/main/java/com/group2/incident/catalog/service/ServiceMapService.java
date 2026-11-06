package com.group2.incident.catalog.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.ServiceDependenciesResponse;
import com.group2.incident.catalog.dto.ServiceMapEdgeDto;
import com.group2.incident.catalog.dto.ServiceMapNodeDto;
import com.group2.incident.catalog.dto.ServiceMapResponse;
import com.group2.incident.catalog.dto.ServiceResponse;
import com.group2.incident.catalog.dto.UpdateDependenciesRequest;
import com.group2.incident.catalog.entity.ServiceDependency;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceDependencyRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.monitor.service.MonitorService;
import com.group2.incident.monitor.service.ServiceHealth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service dependencies, the service map, and the blast radius (FR-14.2, #154). Links are entered by
 * hand for now; a link is a possible relationship, not proof of a shared cause.
 */
@Service
@RequiredArgsConstructor
public class ServiceMapService {

	private final ServiceRepository serviceRepository;
	private final ServiceDependencyRepository dependencyRepository;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final MonitorService monitorService;

	/** Replaces the services {@code serviceId} depends on. A save that changes nothing writes no audit entry. */
	@Transactional
	public ServiceDependenciesResponse replaceDependencies(Long serviceId, UpdateDependenciesRequest request, Long actorId) {
		// Locked, so two saves of one service's links at once can't mix them.
		ServiceEntity service = serviceRepository.findByIdForUpdate(serviceId)
				.orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
		List<Long> dependsOn = request.dependsOn().stream().distinct().sorted().toList();
		if (dependsOn.contains(serviceId)) {
			throw new FieldValidationException("dependsOn", "A service cannot depend on itself");
		}
		// Filtered by org (D48), so another org's service is "not found" too.
		Map<Long, String> names = namesOf(Stream.concat(dependsOn.stream(), Stream.of(serviceId)).toList());
		dependsOn.stream().filter(id -> !names.containsKey(id)).findFirst().ifPresent(missing -> {
			throw new FieldValidationException("dependsOn", "Service " + missing + " not found");
		});

		List<Long> current = dependencyRepository.findDependsOnIdsByServiceId(serviceId);
		if (current.equals(dependsOn)) {
			return new ServiceDependenciesResponse(serviceId, dependsOn);
		}
		Map<Long, String> currentNames = namesOf(current);
		dependencyRepository.deleteByServiceId(serviceId);
		dependencyRepository.saveAll(dependsOn.stream().map(id -> new ServiceDependency(serviceId, id)).toList());

		AppUser actor = actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
		auditService.record(actor, AuditAction.SERVICE_DEPENDENCIES_UPDATED, AuditItemType.SERVICE, serviceId,
				"%s depends on: %s -> %s".formatted(service.getName(), listOf(current, currentNames), listOf(dependsOn, names)));
		return new ServiceDependenciesResponse(serviceId, dependsOn);
	}

	/** Every service with its health from its monitors, and every link, from the dependent service to the one it needs. */
	@Transactional(readOnly = true)
	public ServiceMapResponse getServiceMap() {
		Map<Long, ServiceHealth> health = monitorService.healthByService();
		List<ServiceMapNodeDto> nodes = serviceRepository.findAll(Sort.by("id")).stream()
				.map(s -> new ServiceMapNodeDto(s.getId(), s.getName(), s.getCriticality(),
						health.getOrDefault(s.getId(), ServiceHealth.UNKNOWN)))
				.toList();
		List<ServiceMapEdgeDto> edges = dependencyRepository.findAllByOrderByServiceIdAscDependsOnIdAsc().stream()
				.map(d -> new ServiceMapEdgeDto(d.getServiceId(), d.getDependsOnId()))
				.toList();
		return new ServiceMapResponse(nodes, edges);
	}

	/** What breaks if {@code serviceId} fails: every service that depends on it, directly or through others. Cycles stop. */
	@Transactional(readOnly = true)
	public List<ServiceResponse> getBlastRadius(Long serviceId) {
		if (!serviceRepository.existsById(serviceId)) {
			throw new NotFoundException("Service " + serviceId + " not found");
		}
		Map<Long, List<Long>> dependents = new HashMap<>();
		for (ServiceDependency link : dependencyRepository.findAll()) {
			dependents.computeIfAbsent(link.getDependsOnId(), k -> new ArrayList<>()).add(link.getServiceId());
		}

		Set<Long> affected = new LinkedHashSet<>();
		Queue<Long> queue = new ArrayDeque<>(List.of(serviceId));
		while (!queue.isEmpty()) {
			for (Long dependent : dependents.getOrDefault(queue.poll(), List.of())) {
				if (!dependent.equals(serviceId) && affected.add(dependent)) {
					queue.add(dependent);
				}
			}
		}
		return serviceRepository.findAllById(affected).stream()
				.map(ServiceResponse::from)
				.sorted(Comparator.comparing(ServiceResponse::name))
				.toList();
	}

	private Map<Long, String> namesOf(List<Long> ids) {
		return serviceRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(ServiceEntity::getId, ServiceEntity::getName, (a, b) -> a));
	}

	private static String listOf(List<Long> ids, Map<Long, String> names) {
		return ids.stream().map(id -> names.getOrDefault(id, "#" + id)).collect(Collectors.joining(", ", "[", "]"));
	}
}
