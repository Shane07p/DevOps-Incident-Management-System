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
	 * Every public update, oldest first, on the incidents of these services that the page may list: those the
	 * team has not finished, and the others with an update since {@code since}. All of an incident's updates come
	 * back, so the time it entered RESOLVED is never cut off.
	 */
	@Query("""
			select u from PublicUpdate u join fetch u.incident i
			where i.service.id in :serviceIds
			  and (i.status not in :finished
			       or exists (select 1 from PublicUpdate v where v.incident = i and v.createdAt >= :since))
			order by u.createdAt asc, u.id asc
			""")
	List<PublicUpdate> findForStatusPage(
			@Param("serviceIds") Collection<Long> serviceIds,
			@Param("finished") Collection<IncidentStatus> finished,
			@Param("since") Instant since);
}
