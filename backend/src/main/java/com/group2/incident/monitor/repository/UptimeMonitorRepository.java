package com.group2.incident.monitor.repository;

import com.group2.incident.monitor.entity.UptimeMonitor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UptimeMonitorRepository extends JpaRepository<UptimeMonitor, Long> {

	@EntityGraph(attributePaths = "service")
	List<UptimeMonitor> findAllByOrderByNameAscIdAsc();

	@EntityGraph(attributePaths = "service")
	Optional<UptimeMonitor> findWithServiceById(Long id);

	List<UptimeMonitor> findAllByEnabledTrueAndLastCheckedAtIsNotNull();

	/** For MonitorWorker, run as root: the enabled monitors due at {@code now} in every org, oldest first. */
	@Query("SELECT new com.group2.incident.monitor.repository.DueMonitor(m.id, m.orgId, m.version, m.url, m.expectedStatus) "
			+ "FROM UptimeMonitor m WHERE m.enabled = true AND m.nextCheckAt <= :now ORDER BY m.nextCheckAt ASC, m.id ASC")
	List<DueMonitor> findDue(@Param("now") Instant now);
}
