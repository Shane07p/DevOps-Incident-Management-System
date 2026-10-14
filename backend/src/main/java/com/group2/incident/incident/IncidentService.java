package com.group2.incident.incident;

import com.group2.incident.audit.AuditService;
import com.group2.incident.catalog.ServiceEntity;
import com.group2.incident.catalog.ServiceRepository;
import com.group2.incident.catalog.SlaTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final ServiceRepository serviceRepository;
    private final TimelineRepository timelineRepository;
    private final AuditService auditService;
    private final Clock clock;

    public IncidentService(IncidentRepository incidentRepository, ServiceRepository serviceRepository,
                           TimelineRepository timelineRepository, AuditService auditService, Clock clock) {
        this.incidentRepository = incidentRepository;
        this.serviceRepository = serviceRepository;
        this.timelineRepository = timelineRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Incident createIncident(CreateIncidentRequest request, Long reporterId) {
        ServiceEntity service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new RuntimeException("Service not found"));

        SlaTarget sla = service.getTargets().get(request.severity());

        Incident incident = new Incident();
        incident.setTitle(request.title());
        incident.setDescription(request.description());
        incident.setServiceId(service.getId());
        incident.setSeverity(request.severity());
        incident.setStatus("OPEN");
        incident.setReporterId(reporterId);
        incident.setCreatedAt(OffsetDateTime.now(clock));
        incident.setResponseTargetMinutes(sla.getResponseMinutes());
        incident.setResolutionTargetMinutes(sla.getResolutionMinutes());
        Incident savedIncident = incidentRepository.save(incident);

        TimelineEntry timeline = new TimelineEntry();
        timeline.setIncidentId(savedIncident.getId());
        timeline.setActorId(reporterId);
        timeline.setEventType("INCIDENT_CREATED");
        timeline.setDetails("Reported as " + request.severity() + " on " + service.getName());
        timeline.setRecordedAt(OffsetDateTime.now(clock));
        timelineRepository.save(timeline);

        auditService.record(reporterId, "INCIDENT_CREATED", "INCIDENT", savedIncident.getId(), "Incident reported");

        return savedIncident;
    }
}