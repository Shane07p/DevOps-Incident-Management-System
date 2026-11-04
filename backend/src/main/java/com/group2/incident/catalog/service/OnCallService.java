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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
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

    private record ShiftKey(Long responderId, Instant startTime, Instant endTime) {
    }

    @Transactional(readOnly = true)
    public OnCallScheduleResponse getSchedule(Long serviceId) {
        ServiceEntity service = serviceRepository.findWithDetailsById(serviceId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
        List<OnCallShift> shifts = onCallShiftRepository.findByServiceIdOrderByStartTimeAsc(serviceId);
        return OnCallScheduleResponse.from(service, shifts);
    }

    @Transactional
    public OnCallScheduleResponse updateSchedule(Long serviceId, UpdateOnCallScheduleRequest request) {
        return updateSchedule(serviceId, request, null);
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

        Map<ShiftKey, AppUser> newMap = new LinkedHashMap<>();
        List<ShiftRequest> sortedRequests = shiftRequests.stream()
                .sorted(Comparator.comparing(ShiftRequest::startTime))
                .toList();
        for (ShiftRequest sr : sortedRequests) {
            Membership responderMember = orgMembers.find(sr.responderId())
                    .orElseThrow(() -> new FieldValidationException("shifts", "Responder user " + sr.responderId() + " not found"));
            if (!responderMember.isEnabled()) {
                throw new FieldValidationException("shifts", "Responder must be an enabled user");
            }
            AppUser responder = responderMember.getUser();
            Instant start = sr.startTime().truncatedTo(ChronoUnit.MICROS);
            Instant end = sr.endTime().truncatedTo(ChronoUnit.MICROS);
            newMap.put(new ShiftKey(sr.responderId(), start, end), responder);
        }

        AppUser previousFallback = service.getFallbackContact();
        List<OnCallShift> existingShifts = onCallShiftRepository.findByServiceIdOrderByStartTimeAsc(serviceId);

        Map<ShiftKey, OnCallShift> existingMap = new LinkedHashMap<>();
        for (OnCallShift s : existingShifts) {
            Instant start = s.getStartTime().truncatedTo(ChronoUnit.MICROS);
            Instant end = s.getEndTime().truncatedTo(ChronoUnit.MICROS);
            existingMap.put(new ShiftKey(s.getResponder().getId(), start, end), s);
        }

        boolean fallbackChanged = (previousFallback == null)
                || !previousFallback.getId().equals(fallbackContact.getId());

        List<ShiftKey> addedKeys = newMap.keySet().stream()
                .filter(k -> !existingMap.containsKey(k))
                .sorted(Comparator.comparing(ShiftKey::startTime).thenComparing(ShiftKey::responderId))
                .toList();

        List<ShiftKey> removedKeys = existingMap.keySet().stream()
                .filter(k -> !newMap.containsKey(k))
                .sorted(Comparator.comparing(ShiftKey::startTime).thenComparing(ShiftKey::responderId))
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

        List<OnCallShift> savedShifts = new ArrayList<>();
        for (ShiftRequest sr : shiftRequests) {
            AppUser responder = newMap.get(new ShiftKey(sr.responderId(),
                    sr.startTime().truncatedTo(ChronoUnit.MICROS),
                    sr.endTime().truncatedTo(ChronoUnit.MICROS)));
            OnCallShift shift = new OnCallShift(service, responder, sr.startTime(), sr.endTime());
            savedShifts.add(onCallShiftRepository.save(shift));
        }

        AppUser actor = resolveActor(actorId);
        auditService.record(
                actor,
                AuditAction.ON_CALL_SCHEDULE_UPDATED,
                AuditItemType.SERVICE,
                serviceId,
                String.join("; ", changes)
        );

        return OnCallScheduleResponse.from(service, savedShifts);
    }

    private AppUser resolveActor(Long actorId) {
        if (actorId != null) {
            return appUserRepository.findById(actorId).orElse(null);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return appUserRepository.findById(Long.parseLong(jwt.getSubject())).orElse(null);
            } catch (Exception ignored) {
            }
        }
        return null;
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
