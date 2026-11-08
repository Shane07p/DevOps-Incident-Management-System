package com.group2.incident.auth.repository;

import com.group2.incident.auth.entity.PersonalAccessToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, Long> {

	@EntityGraph(attributePaths = { "user" })
	Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

	List<PersonalAccessToken> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	Optional<PersonalAccessToken> findByIdAndUserId(Long id, Long userId);

	@Query("select t from PersonalAccessToken t where t.orgId = :orgId and t.user.id = :userId and lower(t.name) = lower(:name) and t.revokedAt is null")
	Optional<PersonalAccessToken> findActiveByOrgIdAndUserIdAndNameIgnoreCase(
			@Param("orgId") Long orgId,
			@Param("userId") Long userId,
			@Param("name") String name
	);

	@Modifying
	@Query("update PersonalAccessToken t set t.lastUsedAt = :now where t.id = :id")
	void updateLastUsedAt(@Param("id") Long id, @Param("now") Instant now);
}

