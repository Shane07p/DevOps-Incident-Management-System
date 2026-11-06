package com.group2.incident.monitor.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.monitor.dto.MonitorRequest;
import com.group2.incident.monitor.dto.MonitorResponse;
import com.group2.incident.monitor.entity.UptimeMonitor;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Monitor settings: everyone reads them, Admins create and change them, and every change is audited (FR-13.1). */
@Service
@RequiredArgsConstructor
public class MonitorService {

	private final UptimeMonitorRepository monitorRepository;
	private final ServiceRepository serviceRepository;
	private final AppUserRepository appUserRepository;
	private final MonitorTargetGuard targetGuard;
	private final AuditService auditService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<MonitorResponse> list() {
		return monitorRepository.findAllByOrderByNameAscIdAsc().stream().map(MonitorResponse::from).toList();
	}

	@Transactional
	public MonitorResponse create(MonitorRequest request, Long actorId) {
		ServiceEntity service = requireService(request.serviceId());
		targetGuard.check(request.url());
		UptimeMonitor monitor = monitorRepository.save(new UptimeMonitor(service, request.name().strip(), request.url().strip(),
				request.expectedStatus(), request.intervalSeconds(), request.failureThreshold(), request.enabled(), clock.instant()));
		auditService.record(actor(actorId), AuditAction.MONITOR_CREATED, AuditItemType.MONITOR, monitor.getId(), describe(monitor));
		return MonitorResponse.from(monitor);
	}

	/** A save that changes nothing writes no audit entry and leaves the schedule alone, as with services. */
	@Transactional
	public MonitorResponse update(Long id, MonitorRequest request, Long actorId) {
		UptimeMonitor monitor = monitorRepository.findWithServiceById(id)
				.orElseThrow(() -> new NotFoundException("Monitor " + id + " not found"));
		ServiceEntity service = requireService(request.serviceId());
		targetGuard.check(request.url());

		String before = describe(monitor);
		String after = describe(request.name().strip(), request.url().strip(), request.expectedStatus(), request.intervalSeconds(),
				request.failureThreshold(), request.enabled(), service.getName());
		if (before.equals(after)) {
			return MonitorResponse.from(monitor);
		}
		monitor.change(service, request.name().strip(), request.url().strip(), request.expectedStatus(), request.intervalSeconds(),
				request.failureThreshold(), request.enabled(), clock.instant());
		auditService.record(actor(actorId), AuditAction.MONITOR_UPDATED, AuditItemType.MONITOR, id, "Was: " + before + ". Now: " + after);
		return MonitorResponse.from(monitor);
	}

	// Filtered by org (D48), so another org's service is "not found" too.
	private ServiceEntity requireService(Long serviceId) {
		return serviceRepository.findById(serviceId)
				.orElseThrow(() -> new FieldValidationException("serviceId", "Service not found"));
	}

	private AppUser actor(Long actorId) {
		return actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
	}

	private static String describe(UptimeMonitor monitor) {
		return describe(monitor.getName(), monitor.getUrl(), monitor.getExpectedStatus(), monitor.getIntervalSeconds(),
				monitor.getFailureThreshold(), monitor.isEnabled(), monitor.getService().getName());
	}

	private static String describe(String name, String url, int expectedStatus, int intervalSeconds, int failureThreshold,
			boolean enabled, String serviceName) {
		return "%s on %s: GET %s every %d s, expects HTTP %d, alerts after %d failures in a row, %s".formatted(
				name, serviceName, url, intervalSeconds, expectedStatus, failureThreshold, enabled ? "on" : "off");
	}
}
