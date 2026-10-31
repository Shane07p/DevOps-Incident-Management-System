
package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertInboxItemResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.Alert;
import com.group2.incident.alert.entity.AlertStatus;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.repository.AlertRepository;
import com.group2.incident.alert.util.PayloadFingerprintUtil;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.ConflictException;
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
    public AlertAcceptedResponse processAlert(
            MonitoringTool tool,
            AlertRequest request) {

        ServiceEntity service = serviceRepository
                .findByNameIgnoreCaseForUpdate(request.service())
                .orElseThrow(() ->
                        new FieldValidationException(
                                "service", "Service not found"));

        String canonicalPayload =
                PayloadFingerprintUtil.canonicalPayload(
                        request, service.getId());

        String fingerprint =
                PayloadFingerprintUtil.fingerprint(canonicalPayload);

        if (alertDeduplicationService.findExisting(
                request.source(),
                request.eventId(),
                fingerprint).isPresent()) {

            return AlertAcceptedResponse.of(
                    request.eventId(), request.source());
        }

        Optional<Incident> match = alertCorrelationService
                .findMatchingIncident(
                        service.getId(),
                        request.source(),
                        request.correlationKey());

        Incident incident =
                match.orElseGet(() -> createIncident(service, request));

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
                match.isPresent()
                        ? AlertStatus.ATTACHED
                        : AlertStatus.NEW,
                now
        );

        try {
            alertRepository.saveAndFlush(alert);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "Duplicate eventId with changed payload");
        }

        timelineEntryRepository.save(new TimelineEntry(
                incident,
                null,
                TimelineEventType.ALERT_ATTACHED,
                "Alert from " + request.source()
                        + " attached to incident "
                        + incident.getReference(),
                now
        ));

        return AlertAcceptedResponse.of(
                request.eventId(), request.source());
    }

    // Creates an alert generated internally by the uptime monitor.
    @Transactional
    public Alert createMonitorAlert(AlertRequest request) {

        processAlert(null, request);

        return alertRepository.findBySourceAndEventId(
                request.source(),
                request.eventId()
        ).orElseThrow(() ->
                new IllegalStateException(
                        "Monitor alert was not created"));
    }

    @Transactional(readOnly = true)
    public List<AlertInboxItemResponse> getInboxAlerts() {

        List<Alert> alerts =
                alertRepository.findAllByOrderByCreatedAtDescIdDesc();

        Map<Long, Long> alertsPerIncident = alerts.stream()
                .collect(Collectors.groupingBy(
                        alert -> alert.getIncident().getId(),
                        Collectors.counting()));

        return alerts.stream()
                .map(alert -> new AlertInboxItemResponse(
                        alert.getId(),
                        alert.getSource(),
                        alert.getService().getName(),
                        alert.getIncident().getId(),
                        Optional.ofNullable(
                                alert.getIncident().getAssignee())
                                .map(AppUser::getId)
                                .orElse(null),
                        alert.getStatus().name(),
                        alert.getCorrelationKey(),
                        alert.getCreatedAt(),
                        alertsPerIncident.get(
                                alert.getIncident().getId())
                ))
                .toList();
    }

    private Incident createIncident(
            ServiceEntity service,
            AlertRequest request) {

        IncidentResponse response =
                incidentService.createIncidentFromAlert(
                        new CreateIncidentRequest(
                                request.title(),
                                request.description(),
                                service.getId(),
                                Severity.valueOf(request.severity())
                        ),
                        request.source(),
                        request.correlationKey()
                );

        return incidentRepository.getReferenceById(
                response.id());
    }
}
