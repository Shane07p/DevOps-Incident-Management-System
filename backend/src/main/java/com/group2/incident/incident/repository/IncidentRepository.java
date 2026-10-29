package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Incident;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	// The list shows only the service name; reporter and assignee stay unloaded.
	@EntityGraph(attributePaths = "service")
	List<Incident> findAllByOrderByCreatedAtDescIdDesc();

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	Optional<Incident> findWithDetailsById(Long id);
}
