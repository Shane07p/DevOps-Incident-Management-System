package com.settle.incident.repository;

import com.settle.incident.entity.EscalationPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, Long> {

	@EntityGraph(attributePaths = {"steps", "steps.responder", "service"})
	Optional<EscalationPolicy> findByServiceId(Long serviceId);
}
