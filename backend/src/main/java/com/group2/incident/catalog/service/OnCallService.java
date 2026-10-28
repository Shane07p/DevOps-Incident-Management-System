package com.group2.incident.catalog.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.dto.OnCallScheduleResponse;
import com.group2.incident.catalog.dto.ShiftRequest;
import com.group2.incident.catalog.dto.UpdateOnCallScheduleRequest;
import com.group2.incident.catalog.entity.OnCallShift;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.OnCallShiftRepository;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnCallService {

    private final ServiceRepository serviceRepository;
    private final OnCallShiftRepository onCallShiftRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OnCallScheduleResponse getSchedule(Long serviceId) {
        ServiceEntity service = serviceRepository.findWithDetailsById(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
        List<OnCallShift> shifts = onCallShiftRepository.findByServiceIdOrderByStartTimeAsc(serviceId);
        return OnCallScheduleResponse.from(service, shifts);
    }

    @Transactional
    public OnCallScheduleResponse updateSchedule(Long serviceId, UpdateOnCallScheduleRequest request) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));

        AppUser fallbackContact = appUserRepository.findById(request.fallbackContactId())
                .orElseThrow(() -> new FieldValidationException("fallbackContactId", "Fallback contact user not found"));

        if (!fallbackContact.isEnabled()) {
            throw new FieldValidationException("fallbackContactId", "Fallback contact must be an enabled user");
        }

        List<ShiftRequest> shiftRequests = request.shifts() != null ? request.shifts() : List.of();
        validateShifts(shiftRequests);

        service.changeFallbackContact(fallbackContact);

        onCallShiftRepository.deleteByServiceId(serviceId);

        List<OnCallShift> savedShifts = new ArrayList<>();
        for (ShiftRequest sr : shiftRequests) {
            AppUser responder = appUserRepository.findById(sr.responderId())
                    .orElseThrow(() -> new FieldValidationException("shifts", "Responder user " + sr.responderId() + " not found"));
            if (!responder.isEnabled()) {
                throw new FieldValidationException("shifts", "Responder must be an enabled user");
            }
            OnCallShift shift = new OnCallShift(service, responder, sr.startTime(), sr.endTime());
            savedShifts.add(onCallShiftRepository.save(shift));
        }

        return OnCallScheduleResponse.from(service, savedShifts);
    }

    public AppUser resolveAssignee(ServiceEntity service, Instant at) {
        if (service == null) {
            return null;
        }

        return onCallShiftRepository.findActiveShiftForService(service.getId(), at)
                .map(OnCallShift::getResponder)
                .orElseGet(service::getFallbackContact);
    }

    public void validateShifts(List<ShiftRequest> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return;
        }

        for (ShiftRequest shift : shifts) {
            if (shift.startTime() == null || shift.endTime() == null) {
                throw new FieldValidationException("shifts", "Shift start time and end time are required");
            }
            if (!shift.startTime().isBefore(shift.endTime())) {
                throw new FieldValidationException("shifts", "Shift end time must be after start time");
            }
        }

        List<ShiftRequest> sorted = shifts.stream()
                .sorted(Comparator.comparing(ShiftRequest::startTime))
                .toList();

        for (int i = 0; i < sorted.size() - 1; i++) {
            ShiftRequest current = sorted.get(i);
            ShiftRequest next = sorted.get(i + 1);
            if (next.startTime().isBefore(current.endTime())) {
                throw new FieldValidationException("shifts", "Shifts cannot overlap for the same service");
            }
        }
    }
}
