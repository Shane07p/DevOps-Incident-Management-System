package com.group2.incident.monitor.repository;

import com.group2.incident.monitor.entity.MonitorCheckResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorCheckResultRepository extends JpaRepository<MonitorCheckResult, Long> {

	List<MonitorCheckResult> findByMonitorIdOrderByCheckedAtAscIdAsc(Long monitorId);
}
