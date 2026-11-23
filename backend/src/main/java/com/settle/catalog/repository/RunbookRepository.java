package com.settle.catalog.repository;

import com.settle.catalog.entity.Runbook;
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
