package com.group2.incident.incident.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.catalog.service.ServiceCatalogService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentServiceDto;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.IncidentUserDto;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final ServiceCatalogService serviceCatalogService;
	private final OnCallService onCallService;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final EscalationService escalationService;
	private final Clock clock;

	@Transactional
	public IncidentResponse createIncident(CreateIncidentRequest request, Long reporterUserId) {
		AppUser reporter = null;
		if (reporterUserId != null) {
			reporter = appUserRepository.findById(reporterUserId)
					.orElseThrow(() -> new NotFoundException("User " + reporterUserId + " not found"));
		}

		ServiceEntity service = serviceCatalogService.requireForIncident(request.serviceId());

		// Copied now and never changed afterwards, even if the service's targets change.
		SlaTarget target = service.getTargets().get(request.severity());
		if (target == null) {
			throw new FieldValidationException("severity",
					"Service " + service.getName() + " has no SLA target for " + request.severity());
		}

		Instant now = clock.instant();

		Incident incident = new Incident(
				request.title(),
				request.description(),
				service,
				request.severity(),
				IncidentStatus.OPEN,
				reporter,
				now,
				target.getResponseMinutes(),
				target.getResolutionMinutes()
		);
		// Whoever is on call now, or the service's fallback contact outside every shift (FR-15).
		AppUser assignee = onCallService.resolveAssignee(service, now);
		if (assignee != null) {
			incident.assignTo(assignee);
		}
		incident = incidentRepository.save(incident);

		String details = "Reported as " + request.severity() + " on " + service.getName();
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				reporter,
				TimelineEventType.INCIDENT_CREATED,
				details,
				now
		);
		List<TimelineEntry> timeline = new ArrayList<>();
		timeline.add(timelineEntryRepository.save(timelineEntry));

		if (assignee != null) {
			// A system action, so there is no actor (FR-05.1).
			timeline.add(timelineEntryRepository.save(new TimelineEntry(
					incident,
					null,
					TimelineEventType.ASSIGNED,
					"Assigned to " + assignee.getDisplayName(),
					now
			)));
		}

		auditService.record(
				reporter,
				AuditAction.INCIDENT_CREATED,
				AuditItemType.INCIDENT,
				incident.getId(),
				"Created incident " + incident.getReference() + " on service " + service.getName()
		);

		escalationService.createTaskForIncident(incident, now);

		return toResponse(incident, timeline);
	}

	@Transactional
	public IncidentResponse acknowledgeIncident(Long id, Long userId) {
		AppUser user = null;
		if (userId != null) {
			user = appUserRepository.findById(userId)
					.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
		}

		Instant now = clock.instant();
		Incident incident = incidentRepository.findById(id)
				.orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));

		if (incident.getStatus() != IncidentStatus.OPEN) {
			throw new ConflictException("Incident is not OPEN; current status is " + incident.getStatus());
		}

		incident.acknowledge(user);
		incident = incidentRepository.save(incident);

		escalationService.cancelTaskForIncident(id, now);

		String details = "Acknowledged" + (user != null ? " by " + user.getDisplayName() : "");
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				user,
				TimelineEventType.ACKNOWLEDGED,
				details,
				now
		);
		timelineEntryRepository.save(timelineEntry);

		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(id);
		return toResponse(incident, timeline);
	}

	@Transactional(readOnly = true)
	public IncidentResponse getIncident(Long id) {
		Incident incident = incidentRepository.findWithDetailsById(id)
				.orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));
		List<TimelineEntry> timeline = timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(id);
		return toResponse(incident, timeline);
	}

	@Transactional(readOnly = true)
	public List<IncidentSummaryResponse> listIncidents() {
		return incidentRepository.findAllByOrderByCreatedAtDescIdDesc()
				.stream()
				.map(i -> new IncidentSummaryResponse(
						i.getId(),
						i.getReference(),
						i.getTitle(),
						i.getService().getName(),
						i.getSeverity(),
						i.getStatus(),
						i.getCreatedAt()
				))
				.toList();
	}

	private static IncidentResponse toResponse(Incident incident, List<TimelineEntry> timeline) {
		IncidentServiceDto serviceDto = new IncidentServiceDto(
				incident.getService().getId(),
				incident.getService().getName()
		);
		IncidentUserDto reporterDto = incident.getReporter() != null
				? new IncidentUserDto(incident.getReporter().getId(), incident.getReporter().getDisplayName())
				: null;
		IncidentUserDto assigneeDto = incident.getAssignee() != null
				? new IncidentUserDto(incident.getAssignee().getId(), incident.getAssignee().getDisplayName())
				: null;

		List<TimelineEntryResponse> timelineResponses = timeline.stream()
				.map(t -> new TimelineEntryResponse(
						t.getId(),
						t.getEventType(),
						t.getActor() != null ? t.getActor().getDisplayName() : null,
						t.getDetails(),
						t.getRecordedAt()
				))
				.toList();

		return new IncidentResponse(
				incident.getId(),
				incident.getReference(),
				incident.getTitle(),
				incident.getDescription(),
				serviceDto,
				incident.getSeverity(),
				incident.getStatus(),
				reporterDto,
				assigneeDto,
				incident.getCreatedAt(),
				incident.getResponseTargetMinutes(),
				incident.getResolutionTargetMinutes(),
				incident.getResponseDueAt(),
				incident.getResolutionDueAt(),
				timelineResponses
		);
	}
}
