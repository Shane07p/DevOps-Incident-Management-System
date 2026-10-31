package com.group2.incident.monitor.repository;

import com.group2.incident.monitor.entity.MonitorCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MonitorCheckResultRepository
        extends JpaRepository<MonitorCheckResult, Long> {

    List<MonitorCheckResult> findByMonitor_IdOrderByCheckedAtDescIdDesc(
            Long monitorId
    );
    java.util.Optional<MonitorCheckResult>
findFirstByMonitor_IdOrderByCheckedAtDescIdDesc(Long monitorId);
}