package com.group2.incident.org.repository;

import com.group2.incident.auth.entity.Role;
import com.group2.incident.org.entity.Membership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

	@EntityGraph(attributePaths = { "org", "user" })
	Optional<Membership> findByUserIdAndOrgId(Long userId, Long orgId);

	@EntityGraph(attributePaths = { "org", "user" })
	Optional<Membership> findFirstByUserIdAndEnabledTrue(Long userId);

	@EntityGraph(attributePaths = { "user" })
	List<Membership> findAllByOrgIdOrderByIdAsc(Long orgId);

	long countByOrgIdAndRoleAndEnabledTrue(Long orgId, Role role);

}
