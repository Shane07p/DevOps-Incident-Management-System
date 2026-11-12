package com.group2.incident.postmortem.repository;

import com.group2.incident.postmortem.entity.Postmortem;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostmortemRepository extends JpaRepository<Postmortem, Long> {

	@EntityGraph(attributePaths = { "author", "reviewedBy" })
	Optional<Postmortem> findByIncidentId(Long incidentId);
}
