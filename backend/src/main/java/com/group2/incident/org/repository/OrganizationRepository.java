package com.group2.incident.org.repository;

import com.group2.incident.org.entity.Organization;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

	Optional<Organization> findBySlug(String slug);

	boolean existsBySlug(String slug);

	// Serialises incident numbering within one org (IncidentService).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Organization o where o.id = :id")
	Optional<Organization> findLockedById(@Param("id") Long id);

}
