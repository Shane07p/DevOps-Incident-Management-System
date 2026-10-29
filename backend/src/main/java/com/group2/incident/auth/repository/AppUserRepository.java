package com.group2.incident.auth.repository;

import com.group2.incident.auth.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	// lower() on both sides, so the case-insensitive unique index from V1 applies.
	@Query("select u from AppUser u where lower(u.username) = lower(:username)")
	Optional<AppUser> findByUsername(String username);

	// One UPDATE of the token version alone: saving a loaded user would write back its other
	// columns too, and could undo a role change committed in the meantime.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AppUser u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
	int revokeTokens(@Param("id") long id);

}
