package com.group2.incident.telemetry.repository;

import com.group2.incident.telemetry.entity.TelemetryConnection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryConnectionRepository extends JpaRepository<TelemetryConnection, Long> {

	/** The current org's connection: the table holds at most one per org, and Hibernate filters by org (D48). */
	Optional<TelemetryConnection> findFirstByOrderByIdAsc();
}
