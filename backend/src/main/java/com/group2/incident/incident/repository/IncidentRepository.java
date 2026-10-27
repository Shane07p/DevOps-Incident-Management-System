package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	List<Incident> findAllByOrderByCreatedAtDescIdDesc();

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	Optional<Incident> findWithDetailsById(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select i from Incident i
			where i.service.id = :serviceId
			  and i.correlationSource = :source
			  and i.correlationKey = :correlationKey
			  and i.status not in :terminalStatuses
			order by i.createdAt asc, i.id asc
			""")
	List<Incident> findActiveByCorrelationForUpdate(
			@Param("serviceId") Long serviceId,
			@Param("source") String source,
			@Param("correlationKey") String correlationKey,
			@Param("terminalStatuses") List<IncidentStatus> terminalStatuses);
}
