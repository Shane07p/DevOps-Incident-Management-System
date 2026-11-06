package com.group2.incident.catalog.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
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
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.service.OrgMembers;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnCallService {

    private final ServiceRepository serviceRepository;
    private final OnCallShiftRepository onCallShiftRepository;
    private final OrgMembers orgMembers;
    private final AppUserRepository appUserRepository;
    private final AuditService auditService;
    private final Clock clock;

    // A shift as compared between saves. PostgreSQL keeps microseconds, so a saved shift is compared
    // at that precision; otherwise sending the same schedule again would look like a change.
    private record ShiftKey(Long responderId, Instant startTime, Instant endTime) {

        static ShiftKey of(Long responderId, Instant startTime, Instant endTime) {
            return new ShiftKey(responderId, startTime.truncatedTo(ChronoUnit.MICROS), endTime.truncatedTo(ChronoUnit.MICROS));
        }
    }

    private static final Comparator<ShiftKey> BY_START = Comparator.comparing(ShiftKey::startTime)
            .thenComparing(ShiftKey::responderId);

    @Transactional(readOnly = true)
    public OnCallScheduleResponse getSchedule(Long serviceId) {
        ServiceEntity service = serviceRepository.findWithDetailsById(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
        List<OnCallShift> shifts = onCallShiftRepository.findByServiceIdOrderByStartTimeAsc(serviceId);
        return OnCallScheduleResponse.from(service, shifts);
    }

    @Transactional
    public OnCallScheduleResponse updateSchedule(Long serviceId, UpdateOnCallScheduleRequest request, Long actorId) {
        ServiceEntity service = serviceRepository.findByIdForUpdate(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));

        // Only a member of this org (D48).
        Membership fallbackMember = orgMembers.find(request.fallbackContactId())
                .orElseThrow(() -> new FieldValidationException("fallbackContactId", "Fallback contact user not found"));

        if (!fallbackMember.isEnabled()) {
            throw new FieldValidationException("fallbackContactId", "Fallback contact must be an enabled user");
        }
        AppUser fallbackContact = fallbackMember.getUser();

        List<ShiftRequest> shiftRequests = request.shifts() != null ? request.shifts() : List.of();
        validateShifts(shiftRequests);

        // Request order is kept: the response lists the shifts as they were sent.
        Map<ShiftKey, AppUser> newMap = new LinkedHashMap<>();
        for (ShiftRequest sr : shiftRequests) {
            Membership responderMember = orgMembers.find(sr.responderId())
                    .orElseThrow(() -> new FieldValidationException("shifts", "Responder user " + sr.responderId() + " not found"));
            if (!responderMember.isEnabled()) {
                throw new FieldValidationException("shifts", "Responder must be an enabled user");
            }
            newMap.put(ShiftKey.of(sr.responderId(), sr.startTime(), sr.endTime()), responderMember.getUser());
        }

        AppUser previousFallback = service.getFallbackContact();
        List<OnCallShift> existingShifts = onCallShiftRepository.findByServiceIdOrderByStartTimeAsc(serviceId);

        Map<ShiftKey, OnCallShift> existingMap = new LinkedHashMap<>();
        for (OnCallShift s : existingShifts) {
            existingMap.put(ShiftKey.of(s.getResponder().getId(), s.getStartTime(), s.getEndTime()), s);
        }

        boolean fallbackChanged = (previousFallback == null)
                || !previousFallback.getId().equals(fallbackContact.getId());

        List<ShiftKey> addedKeys = newMap.keySet().stream()
                .filter(k -> !existingMap.containsKey(k))
                .sorted(BY_START)
                .toList();

        List<ShiftKey> removedKeys = existingMap.keySet().stream()
                .filter(k -> !newMap.containsKey(k))
                .sorted(BY_START)
                .toList();

        boolean shiftsChanged = !addedKeys.isEmpty() || !removedKeys.isEmpty();

        if (!fallbackChanged && !shiftsChanged) {
            return OnCallScheduleResponse.from(service, existingShifts);
        }

        List<String> changes = new ArrayList<>();
        if (fallbackChanged) {
            String prevName = previousFallback != null ? previousFallback.getDisplayName() : "none";
            String newName = fallbackContact.getDisplayName();
            changes.add("fallback contact: " + prevName + " → " + newName);
        }
        for (ShiftKey key : addedKeys) {
            AppUser responder = newMap.get(key);
            changes.add("added shift: " + responder.getDisplayName() + " (" + key.startTime() + " to " + key.endTime() + ")");
        }
        for (ShiftKey key : removedKeys) {
            OnCallShift shift = existingMap.get(key);
            changes.add("removed shift: " + shift.getResponder().getDisplayName() + " (" + key.startTime() + " to " + key.endTime() + ")");
        }

        service.changeFallbackContact(fallbackContact);

        onCallShiftRepository.deleteByServiceId(serviceId);

        // Saved as compared, so the stored times are the ones the next save is compared against.
        List<OnCallShift> savedShifts = new ArrayList<>();
        for (Map.Entry<ShiftKey, AppUser> entry : newMap.entrySet()) {
            ShiftKey key = entry.getKey();
            savedShifts.add(onCallShiftRepository.save(new OnCallShift(service, entry.getValue(), key.startTime(), key.endTime())));
        }

        AppUser actor = actorId != null ? appUserRepository.findById(actorId).orElse(null) : null;
        auditService.record(
                actor,
                AuditAction.ON_CALL_SCHEDULE_UPDATED,
                AuditItemType.SERVICE,
                serviceId,
                String.join("; ", changes)
        );

        return OnCallScheduleResponse.from(service, savedShifts);
    }

    public AppUser resolveAssignee(ServiceEntity service, Instant at) {
        if (service == null) {
            return null;
        }

        return onCallShiftRepository.findActiveShiftsForService(service.getId(), at).stream()
                .findFirst()
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
