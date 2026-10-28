package com.group2.incident.catalog.controller;

import com.group2.incident.catalog.dto.OnCallScheduleResponse;
import com.group2.incident.catalog.dto.UpdateOnCallScheduleRequest;
import com.group2.incident.catalog.service.OnCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{id}/on-call")
@Tag(name = "Service On-Call", description = "On-call shifts and fallback contact per service")
@RequiredArgsConstructor
public class ServiceOnCallController {

    private final OnCallService onCallService;

    @GetMapping
    @Operation(summary = "Get the on-call schedule for a service")
    public OnCallScheduleResponse get(@PathVariable Long id) {
        return onCallService.getSchedule(id);
    }

    @PutMapping
    @Operation(summary = "Update the on-call schedule and fallback contact for a service (Admin)")
    public OnCallScheduleResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOnCallScheduleRequest request) {
        return onCallService.updateSchedule(id, request);
    }
}
