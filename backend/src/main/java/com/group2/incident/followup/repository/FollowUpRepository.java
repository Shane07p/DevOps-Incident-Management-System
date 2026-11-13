package com.group2.incident.followup.repository;

import com.group2.incident.followup.entity.FollowUp;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long>, JpaSpecificationExecutor<FollowUp> {

	@EntityGraph(attributePaths = { "owner", "incident" })
	List<FollowUp> findAllByIncidentIdOrderByCreatedAtAscIdAsc(Long incidentId);

	@Override
	@EntityGraph(attributePaths = { "owner", "incident" })
	List<FollowUp> findAll(Specification<FollowUp> spec, Sort sort);
}
