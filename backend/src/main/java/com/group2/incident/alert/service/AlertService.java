package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertInboxItemResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.entity.AlertStatus;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.util.PayloadFingerprintUtil;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.IncidentService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
	private final AlertValidationService alertValidationService;
	private final AlertDeduplicationService alertDeduplicationService;
	private final AlertCorrelationService alertCorrelationService;
	private final Clock clock;

	@Transactional
	public AlertAcceptedResponse processAlert(MonitoringTool tool, AlertRequest request) {
		AlertRequest validated = alertValidationService.validate(request);
		ServiceEntity service = serviceRepository.findByNameIgnoreCaseForUpdate(validated.service())
				.orElseThrow(() -> new FieldValidationException("service", "Service not found"));

		String canonicalPayload = PayloadFingerprintUtil.canonicalPayload(validated, service.getId());
		String fingerprint = PayloadFingerprintUtil.fingerprint(canonicalPayload);
		if (alertDeduplicationService.findExisting(validated.source(), validated.eventId(), fingerprint).isPresent()) {
			return AlertAcceptedResponse.of(validated.eventId(), validated.source());
		}

		Incident incident = alertCorrelationService
				.findMatchingIncident(service.getId(), validated.source(), validated.correlationKey())
				.orElseGet(() -> createIncident(service, validated));

		Instant now = clock.instant();
		alertRepository.save(new Alert(
				validated.source(),
				validated.eventId(),
				service,
				validated.correlationKey(),
				incident,
				validated.title(),
				validated.description(),
				Severity.valueOf(validated.severity()),
				canonicalPayload,
				fingerprint,
				AlertStatus.ATTACHED,
				now
		));
		timelineEntryRepository.save(new TimelineEntry(
				incident,
				null,
				TimelineEventType.ALERT_ATTACHED,
				"Alert from " + validated.source() + " attached to incident " + incident.getReference(),
				now
		));
		return AlertAcceptedResponse.of(validated.eventId(), validated.source());
	}

	@Transactional(readOnly = true)
	public List<AlertInboxItemResponse> getInboxAlerts() {
		return alertRepository.findAllByOrderByCreatedAtDescIdDesc()
				.stream()
				.map(alert -> new AlertInboxItemResponse(
						alert.getId(),
						alert.getSource(),
						alert.getService().getName(),
						alert.getIncident().getId(),
						alert.getStatus().name(),
						alert.getCorrelationKey(),
						alert.getCreatedAt()
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
