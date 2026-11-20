package com.group2.incident.auth.repository;

import com.group2.incident.auth.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	// lower() on both sides, so the case-insensitive unique index from V10 applies.
	@Query("select u from AppUser u where lower(u.email) = lower(:email)")
	Optional<AppUser> findByEmail(@Param("email") String email);

	// One UPDATE of the token version alone: saving a loaded user would write back its other
	// columns too, and could undo a role change committed in the meantime.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AppUser u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
	int revokeTokens(@Param("id") long id);

	// Linking a Google or GitHub account proves the email; a password set before that was never proved, so it goes,
	// and every token issued so far stops working (D50).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AppUser u set u.passwordHash = null, u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
	int dropPasswordAndRevokeTokens(@Param("id") long id);

}
