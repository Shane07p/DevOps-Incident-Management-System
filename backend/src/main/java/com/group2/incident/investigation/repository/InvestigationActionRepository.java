package com.group2.incident.investigation.repository;

import com.group2.incident.investigation.entity.InvestigationAction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationActionRepository extends JpaRepository<InvestigationAction, Long> {

	Optional<InvestigationAction> findByIdAndInvestigationId(Long id, Long investigationId);
}
