package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.TimelineEntry;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, Long> {

	@EntityGraph(attributePaths = { "actor" })
	List<TimelineEntry> findAllByIncidentIdOrderByRecordedAtAscIdAsc(Long incidentId);
}
