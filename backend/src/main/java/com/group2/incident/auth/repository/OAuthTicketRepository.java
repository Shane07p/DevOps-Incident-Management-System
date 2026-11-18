package com.group2.incident.auth.repository;

import com.group2.incident.auth.entity.OAuthTicket;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthTicketRepository extends JpaRepository<OAuthTicket, Long> {

	Optional<OAuthTicket> findByCodeHash(String codeHash);

	// One UPDATE claims the code: of two requests with the same code, only one changes a row.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update OAuthTicket t set t.usedAt = :now
			where t.id = :id and t.usedAt is null and t.expiresAt > :now
			""")
	int claim(@Param("id") long id, @Param("now") Instant now);
}
