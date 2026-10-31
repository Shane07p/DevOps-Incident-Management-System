
package com.group2.incident.monitor.service;

import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.monitor.dto.MonitorRequest;
import com.group2.incident.monitor.dto.MonitorResponse;
import com.group2.incident.monitor.entity.UptimeMonitor;
import com.group2.incident.monitor.repository.UptimeMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MonitorService {

    private final UptimeMonitorRepository monitorRepository;
    private final ServiceRepository serviceRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<MonitorResponse> getAllMonitors() {
        return monitorRepository.findAllByOrderByIdDesc()
                .stream()
                .map(MonitorResponse::from)
                .toList();
    }

    public MonitorResponse createMonitor(MonitorRequest request) {
        validateUrl(request.url());

        ServiceEntity service = findService(request.serviceId());

        UptimeMonitor monitor = new UptimeMonitor();

        monitor.configure(
                request.name().trim(),
                request.url().trim(),
                request.expectedStatus(),
                request.intervalSeconds(),
                service,
                request.failureThreshold(),
                request.enabled(),
                clock.instant()
        );

        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    public MonitorResponse updateMonitor(Long id, MonitorRequest request) {
        validateUrl(request.url());

        UptimeMonitor monitor = monitorRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Monitor not found: " + id
                ));

        ServiceEntity service = findService(request.serviceId());

        monitor.configure(
                request.name().trim(),
                request.url().trim(),
                request.expectedStatus(),
                request.intervalSeconds(),
                service,
                request.failureThreshold(),
                request.enabled(),
                clock.instant()
        );

        return MonitorResponse.from(monitorRepository.save(monitor));
    }

    private ServiceEntity findService(Long serviceId) {
        return serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Service not found: " + serviceId
                ));
    }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value.trim());

            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Monitor URL must be a valid absolute HTTP or HTTPS URL."
            );
        }
    }
}
