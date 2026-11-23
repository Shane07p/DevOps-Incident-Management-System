package com.settle.org.repository;

import com.settle.auth.entity.Role;
import com.settle.org.entity.Membership;
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
