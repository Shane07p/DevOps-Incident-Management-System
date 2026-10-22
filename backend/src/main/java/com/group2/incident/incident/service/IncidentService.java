package com.group2.incident.incident.service;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.AppService;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.AppServiceRepository;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final AppServiceRepository appServiceRepository;
	private final AppUserRepository appUserRepository;
	private final AuditService auditService;
	private final Clock clock;

	@Transactional
	public IncidentResponse createIncident(CreateIncidentRequest request, Long reporterUserId) {
		AppUser reporter = null;
		if (reporterUserId != null) {
			reporter = appUserRepository.findById(reporterUserId)
					.orElseThrow(() -> new NotFoundException("User " + reporterUserId + " not found"));
		}

		AppService service = appServiceRepository.findWithDetailsById(request.serviceId())
				.orElseThrow(() -> new FieldValidationException("serviceId", "Service not found"));

		SlaTarget target = service.targetFor(request.severity());
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
				target.responseMinutes(),
				target.resolutionMinutes()
		);
		incident = incidentRepository.save(incident);

		String details = "Reported as " + request.severity() + " on " + service.getName();
		TimelineEntry timelineEntry = new TimelineEntry(
				incident,
				reporter,
				TimelineEventType.INCIDENT_CREATED,
				details,
				now
		);
		timelineEntry = timelineEntryRepository.save(timelineEntry);

		auditService.record(
				reporter,
				"INCIDENT_CREATED",
				"INCIDENT",
				incident.getId(),
				"Created incident " + incident.getReference() + " on service " + service.getName()
		);

		return toResponse(incident, List.of(timelineEntry));
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
