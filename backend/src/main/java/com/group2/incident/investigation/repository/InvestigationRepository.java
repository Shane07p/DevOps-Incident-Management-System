package com.group2.incident.investigation.repository;

import com.group2.incident.investigation.entity.Investigation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

	Optional<Investigation> findTopByIncidentIdOrderByCreatedAtDescIdDesc(Long incidentId);
}
