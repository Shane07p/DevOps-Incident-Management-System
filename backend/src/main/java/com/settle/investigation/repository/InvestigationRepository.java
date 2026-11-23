package com.settle.investigation.repository;

import com.settle.investigation.entity.Investigation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

	Optional<Investigation> findTopByIncidentIdOrderByCreatedAtDescIdDesc(Long incidentId);
}
