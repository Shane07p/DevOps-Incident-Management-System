package com.group2.incident.heartbeat.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.heartbeat.dto.HeartbeatPingResponse;
import com.group2.incident.heartbeat.dto.HeartbeatRequest;
import com.group2.incident.heartbeat.dto.HeartbeatResponse;
import com.group2.incident.heartbeat.entity.Heartbeat;
import com.group2.incident.heartbeat.repository.HeartbeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Heartbeat settings: everyone reads them, Admins create and change them, and every change is audited (FR-13.1). */
@Service
@RequiredArgsConstructor
public class HeartbeatService {

	private final HeartbeatRepository heartbeatRepository;
	private final ServiceRepository serviceRepository;
	private final AppUserRepository appUserRepository;
	private final HeartbeatRecorder recorder;
	private final AuditService auditService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<HeartbeatResponse> list() {
		return heartbeatRepository.findAllByOrderByNameAscIdAsc().stream().map(HeartbeatResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public HeartbeatResponse get(Long id) {
		return heartbeatRepository.findWithServiceById(id)
				.map(HeartbeatResponse::from)
				.orElseThrow(() -> new NotFoundException("Heartbeat " + id + " not found"));
	}

	@Transactional
	public HeartbeatResponse create(HeartbeatRequest request, Long actorId) {
		ServiceEntity service = requireService(request.serviceId());
		String token = UUID.randomUUID().toString().replace("-", "");
		Heartbeat heartbeat = heartbeatRepository.save(new Heartbeat(
				service,
				request.name().strip(),
				request.intervalSeconds(),
				request.graceSeconds(),
				token,
				request.enabled(),
				clock.instant()
		));
		auditService.record(actor(actorId), AuditAction.HEARTBEAT_CREATED, AuditItemType.HEARTBEAT, heartbeat.getId(), describe(heartbeat));
		return HeartbeatResponse.from(heartbeat);
	}

	@Transactional
	public HeartbeatResponse update(Long id, HeartbeatRequest request, Long actorId) {
		Heartbeat heartbeat = heartbeatRepository.findWithServiceById(id)
				.orElseThrow(() -> new NotFoundException("Heartbeat " + id + " not found"));
		ServiceEntity service = requireService(request.serviceId());

		String before = describe(heartbeat);
		String after = describe(request.name().strip(), request.intervalSeconds(), request.graceSeconds(), request.enabled(), service.getName());
		if (before.equals(after)) {
			return HeartbeatResponse.from(heartbeat);
		}
		heartbeat.change(service, request.name().strip(), request.intervalSeconds(), request.graceSeconds(), request.enabled(), clock.instant());
		auditService.record(actor(actorId), AuditAction.HEARTBEAT_UPDATED, AuditItemType.HEARTBEAT, id, "Was: " + before + ". Now: " + after);
		return HeartbeatResponse.from(heartbeat);
	}

	/**
	 * A ping from a scheduled job, which has no login: the token alone names the heartbeat, in whichever org
	 * owns it. It is found across orgs, then recorded as that org (D48).
	 */
	public HeartbeatPingResponse ping(String token) {
		Instant now = clock.instant();
		Heartbeat heartbeat = OrgContext.runAsRoot(() -> heartbeatRepository.findByToken(token))
				.orElseThrow(() -> new NotFoundException("Heartbeat not found"));
		return OrgContext.runAs(heartbeat.getOrgId(), () -> recorder.recordPing(heartbeat.getId(), now));
	}

	private ServiceEntity requireService(Long serviceId) {
		return serviceRepository.findById(serviceId)
				.orElseThrow(() -> new FieldValidationException("serviceId", "Service not found"));
	}

	private AppUser actor(Long actorId) {
		return actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
	}

	private static String describe(Heartbeat heartbeat) {
		return describe(heartbeat.getName(), heartbeat.getIntervalSeconds(), heartbeat.getGraceSeconds(),
				heartbeat.isEnabled(), heartbeat.getService().getName());
	}

	private static String describe(String name, int intervalSeconds, int graceSeconds, boolean enabled, String serviceName) {
		return "%s on %s: ping every %d s (grace %d s), %s".formatted(name, serviceName, intervalSeconds, graceSeconds, enabled ? "on" : "off");
	}
}
