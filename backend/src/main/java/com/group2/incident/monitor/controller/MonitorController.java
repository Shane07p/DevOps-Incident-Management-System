
package com.group2.incident.monitor.controller;

import com.group2.incident.monitor.dto.MonitorRequest;
import com.group2.incident.monitor.dto.MonitorResponse;
import com.group2.incident.monitor.service.MonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
@Tag(
        name = "Monitors",
        description = "Uptime monitor management"
)
public class MonitorController {

    private final MonitorService monitorService;

    @GetMapping
    @Operation(summary = "Get all uptime monitors")
    public List<MonitorResponse> getAllMonitors() {
        return monitorService.getAllMonitors();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an uptime monitor")
    public MonitorResponse createMonitor(
            @Valid @RequestBody MonitorRequest request) {
        return monitorService.createMonitor(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an uptime monitor")
    public MonitorResponse updateMonitor(
            @PathVariable Long id,
            @Valid @RequestBody MonitorRequest request) {
        return monitorService.updateMonitor(id, request);
    }
}
