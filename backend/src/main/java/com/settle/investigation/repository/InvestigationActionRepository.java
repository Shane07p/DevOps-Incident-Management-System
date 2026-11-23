package com.settle.investigation.repository;

import com.settle.auth.entity.AppUser;
import com.settle.investigation.entity.ActionDecision;
import com.settle.investigation.entity.InvestigationAction;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestigationActionRepository extends JpaRepository<InvestigationAction, Long> {

	Optional<InvestigationAction> findByIdAndInvestigationId(Long id, Long investigationId);

	@Modifying
	@Query("""
			update InvestigationAction a
			set a.decision = :decision, a.decidedBy = :actor, a.decidedAt = :decidedAt
			where a.id = :id and a.investigation.id = :investigationId and a.decision is null
			""")
	int recordDecision(
			@Param("id") Long id,
			@Param("investigationId") Long investigationId,
			@Param("decision") ActionDecision decision,
			@Param("actor") AppUser actor,
			@Param("decidedAt") Instant decidedAt);
}
