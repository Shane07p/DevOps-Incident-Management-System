package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Incident;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	List<Incident> findAllByOrderByCreatedAtDescIdDesc();

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	Optional<Incident> findWithDetailsById(Long id);
}
