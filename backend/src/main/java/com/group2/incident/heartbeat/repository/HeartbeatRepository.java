package com.group2.incident.heartbeat.repository;

import com.group2.incident.heartbeat.entity.Heartbeat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HeartbeatRepository extends JpaRepository<Heartbeat, Long> {

	@EntityGraph(attributePaths = "service")
	List<Heartbeat> findAllByOrderByNameAscIdAsc();

	@EntityGraph(attributePaths = "service")
	Optional<Heartbeat> findWithServiceById(Long id);

	@EntityGraph(attributePaths = "service")
	Optional<Heartbeat> findByToken(String token);

	/** For HeartbeatWorker, run as root: the enabled heartbeats overdue at {@code now} across all orgs without an active alert. */
	@Query("SELECT new com.group2.incident.heartbeat.repository.DueHeartbeat(h.id, h.orgId, h.version, h.lateAfter) "
			+ "FROM Heartbeat h WHERE h.enabled = true AND h.activeAlertId IS NULL AND h.lateAfter <= :now ORDER BY h.lateAfter ASC, h.id ASC")
	List<DueHeartbeat> findOverdue(@Param("now") Instant now);
}
