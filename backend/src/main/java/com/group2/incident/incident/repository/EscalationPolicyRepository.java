package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.EscalationPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, Long> {

	@EntityGraph(attributePaths = {"steps", "steps.responder", "service"})
	Optional<EscalationPolicy> findByServiceId(Long serviceId);
}
