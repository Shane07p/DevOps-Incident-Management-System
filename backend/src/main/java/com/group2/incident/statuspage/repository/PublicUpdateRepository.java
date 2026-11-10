package com.group2.incident.statuspage.repository;

import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.statuspage.entity.PublicUpdate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PublicUpdateRepository extends JpaRepository<PublicUpdate, Long> {

	/**
	 * The public updates the status page needs for these services, oldest first: every update on an incident
	 * still open inside, and the updates of the last 90 days on the others.
	 */
	@Query("""
			select u from PublicUpdate u join fetch u.incident i
			where i.service.id in :serviceIds
			  and (i.status not in :finished or u.createdAt >= :since)
			order by u.createdAt asc, u.id asc
			""")
	List<PublicUpdate> findForStatusPage(
			@Param("serviceIds") Collection<Long> serviceIds,
			@Param("finished") Collection<IncidentStatus> finished,
			@Param("since") Instant since);
}
