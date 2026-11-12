package com.group2.incident.catalog.repository;

import com.group2.incident.catalog.entity.Runbook;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunbookRepository extends JpaRepository<Runbook, Long> {

	@EntityGraph(attributePaths = "steps")
	List<Runbook> findAllByServiceIdOrderByTitleAscIdAsc(Long serviceId);

	@EntityGraph(attributePaths = { "service", "steps" })
	Optional<Runbook> findWithStepsById(Long id);
}
