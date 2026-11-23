package com.settle.monitor.repository;

import com.settle.monitor.entity.MonitorCheckResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorCheckResultRepository extends JpaRepository<MonitorCheckResult, Long> {

	List<MonitorCheckResult> findByMonitorIdOrderByCheckedAtAscIdAsc(Long monitorId);
}
