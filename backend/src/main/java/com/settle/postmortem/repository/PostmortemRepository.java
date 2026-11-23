package com.settle.postmortem.repository;

import com.settle.postmortem.entity.Postmortem;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PostmortemRepository extends JpaRepository<Postmortem, Long> {

	@EntityGraph(attributePaths = { "author", "reviewedBy" })
	Optional<Postmortem> findByIncidentId(Long incidentId);

	/**
	 * The postmortem read for closure, with a shared row lock held until the closure commits (FOR SHARE). An edit
	 * that has already written the row makes the closure wait and then read the edit; an edit that comes later
	 * waits for the closure. Either way the closure is judged on the postmortem as it is when it commits.
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	Optional<Postmortem> findForClosureByIncidentId(Long incidentId);
}
