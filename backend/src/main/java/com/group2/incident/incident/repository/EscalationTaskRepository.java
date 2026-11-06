package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.EscalationTask;
import com.group2.incident.incident.entity.EscalationTaskState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscalationTaskRepository extends JpaRepository<EscalationTask, Long> {

	@Override
	@EntityGraph(attributePaths = {"incident", "steps", "steps.responder", "incident.service"})
	Optional<EscalationTask> findById(Long id);

	@EntityGraph(attributePaths = {"incident", "steps", "steps.responder", "incident.service"})
	Optional<EscalationTask> findByIncidentId(Long incidentId);

	// Read across every org by the worker, as root; each task then runs in its own org (D48).
	@Query("SELECT new com.group2.incident.incident.repository.OverdueTask(t.id, t.orgId) FROM EscalationTask t "
			+ "WHERE t.state = :state AND t.nextDueAt <= :now AND t.incident.status = com.group2.incident.incident.entity.IncidentStatus.OPEN "
			+ "ORDER BY t.nextDueAt ASC, t.id ASC")
	List<OverdueTask> findOverdueTasks(@Param("state") EscalationTaskState state, @Param("now") Instant now);
}
