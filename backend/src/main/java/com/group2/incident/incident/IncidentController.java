package com.group2.incident.incident;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    public Incident createIncident(@RequestBody CreateIncidentRequest request) {
        return incidentService.createIncident(request, 1L); // 1L is a dummy reporterId
    }

    @GetMapping
    public List<String> listIncidents() {
        return List.of("INC-1: Checkout failing - SEV2");
    }
}