package com.settle.alert.service;

import com.settle.alert.dto.AlertAcceptedResponse;
import com.settle.alert.dto.AlertInboxItemResponse;
import com.settle.alert.dto.AlertRequest;
import com.settle.alert.entity.Alert;
import com.settle.alert.entity.AlertStatus;
import com.settle.alert.entity.MonitoringTool;
import com.settle.alert.repository.AlertRepository;
import com.settle.alert.util.PayloadFingerprintUtil;
import com.settle.auth.entity.AppUser;
import com.settle.catalog.entity.ServiceEntity;
import com.settle.catalog.entity.Severity;
import com.settle.catalog.repository.ServiceRepository;
import com.settle.common.error.ConflictException;
import com.settle.common.error.FieldValidationException;
import com.settle.incident.dto.CreateIncidentRequest;
import com.settle.incident.dto.IncidentResponse;
import com.settle.incident.entity.Incident;
import com.settle.incident.entity.TimelineEntry;
import com.settle.incident.entity.TimelineEventType;
import com.settle.incident.repository.IncidentRepository;
import com.settle.incident.repository.TimelineEntryRepository;
import com.settle.incident.service.IncidentService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertService {

	private final AlertRepository alertRepository;
	private final ServiceRepository serviceRepository;
	private final IncidentRepository incidentRepository;
	private final TimelineEntryRepository timelineEntryRepository;
	private final IncidentService incidentService;
	private final AlertDeduplicationService alertDeduplicationService;
	private final AlertCorrelationService alertCorrelationService;
	private final Clock clock;

	@Transactional
	public AlertAcceptedResponse processAlert(MonitoringTool tool, AlertRequest request) {
		receive(request);
		return AlertAcceptedResponse.of(request.eventId(), request.source());
	}

	/** Our own uptime monitors raise alerts through the same intake as the tools (FR-22.1). */
	@Transactional
	public Alert raiseMonitorAlert(AlertRequest request) {
		return receive(request);
	}

	/** Saves the alert, or returns the one already saved for this source and event id. */
	private Alert receive(AlertRequest request) {
		ServiceEntity service = serviceRepository.findByNameIgnoreCaseForUpdate(request.service())
				.orElseThrow(() -> new FieldValidationException("service", "Service not found"));

		String canonicalPayload = PayloadFingerprintUtil.canonicalPayload(request, service.getId());
		String fingerprint = PayloadFingerprintUtil.fingerprint(canonicalPayload);
		Optional<Alert> existing = alertDeduplicationService.findExisting(request.source(), request.eventId(), fingerprint);
		if (existing.isPresent()) {
			return existing.get();
		}

		Optional<Incident> match = alertCorrelationService
				.findMatchingIncident(service.getId(), request.source(), request.correlationKey());
		Incident incident = match.orElseGet(() -> createIncident(service, request));

		Instant now = clock.instant();
		Alert alert = new Alert(
				request.source(),
				request.eventId(),
				service,
				request.correlationKey(),
				incident,
				request.title(),
				request.description(),
				Severity.valueOf(request.severity()),
				canonicalPayload,
				fingerprint,
				match.isPresent() ? AlertStatus.ATTACHED : AlertStatus.NEW,
				now
		);
		try {
			alertRepository.saveAndFlush(alert);
		}
		catch (DataIntegrityViolationException e) {
			// The same source and event ID for another service, at the same moment: each request locked
			// only its own service, so the unique constraint is what caught it.
			throw new ConflictException("Duplicate eventId with changed payload");
		}
		timelineEntryRepository.save(new TimelineEntry(
				incident,
				null,
				TimelineEventType.ALERT_ATTACHED,
				"Alert from " + request.source() + " attached to incident " + incident.getReference(),
				now
		));
		return alert;
	}

	@Transactional(readOnly = true)
	public List<AlertInboxItemResponse> getInboxAlerts() {
		List<Alert> alerts = alertRepository.findAllByOrderByCreatedAtDescIdDesc();
		Map<Long, Long> alertsPerIncident = alerts.stream()
				.collect(Collectors.groupingBy(alert -> alert.getIncident().getId(), Collectors.counting()));
		return alerts.stream()
				.map(alert -> new AlertInboxItemResponse(
						alert.getId(),
						alert.getSource(),
						alert.getService().getName(),
						alert.getIncident().getId(),
						alert.getIncident().getReference(),
						Optional.ofNullable(alert.getIncident().getAssignee()).map(AppUser::getId).orElse(null),
						alert.getStatus().name(),
						alert.getCorrelationKey(),
						alert.getCreatedAt(),
						alertsPerIncident.get(alert.getIncident().getId())
				))
				.toList();
	}

	private Incident createIncident(ServiceEntity service, AlertRequest request) {
		IncidentResponse response = incidentService.createIncidentFromAlert(
				new CreateIncidentRequest(
						request.title(),
						request.description(),
						service.getId(),
						Severity.valueOf(request.severity())
				),
				request.source(),
				request.correlationKey()
		);
		return incidentRepository.getReferenceById(response.id());
	}
}
