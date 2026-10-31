package com.group2.incident.monitor.repository;

import com.group2.incident.monitor.entity.UptimeMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UptimeMonitorRepository
        extends JpaRepository<UptimeMonitor, Long> {

    List<UptimeMonitor> findAllByOrderByIdDesc();

    List<UptimeMonitor> findAllByEnabledTrue();
}