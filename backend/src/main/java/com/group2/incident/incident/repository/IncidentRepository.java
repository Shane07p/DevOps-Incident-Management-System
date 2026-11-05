package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

	// The list shows only the service name; reporter and assignee stay unloaded.
	@EntityGraph(attributePaths = "service")
	List<Incident> findAllByOrderByCreatedAtDescIdDesc();

	@EntityGraph(attributePaths = { "service", "reporter", "assignee" })
	Optional<Incident> findWithDetailsById(Long id);

	@Query(
			value = """
					select i.id as "incidentId",
					       i.number as "incidentNumber",
					       i.title as "title",
					       s.name as "serviceName",
					       i.severity as "severity",
					       i.status as "status",
					       i.created_at as "createdAt",
					       to_tsvector('english', coalesce(i.title, ''))
					           @@ websearch_to_tsquery('english', :query) as "titleMatch",
					       to_tsvector('english', coalesce(i.description, ''))
					           @@ websearch_to_tsquery('english', :query) as "descriptionMatch",
					       exists (
					           select 1
					           from timeline_entry te
					           where te.incident_id = i.id
					             and te.org_id = :orgId
					             and to_tsvector('english', coalesce(te.details, ''))
					                 @@ websearch_to_tsquery('english', :query)
					       ) as "timelineMatch"
					from incident i
					join service s on s.id = i.service_id and s.org_id = :orgId
					where i.org_id = :orgId
					  and (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					  and (
					      to_tsvector('english', coalesce(i.title, ''))
					          @@ websearch_to_tsquery('english', :query)
					      or to_tsvector('english', coalesce(i.description, ''))
					          @@ websearch_to_tsquery('english', :query)
					      or exists (
					          select 1
					          from timeline_entry te
					          where te.incident_id = i.id
					            and te.org_id = :orgId
					            and to_tsvector('english', coalesce(te.details, ''))
					                @@ websearch_to_tsquery('english', :query)
					      )
					  )
					order by i.created_at desc, i.id desc
					""",
			countQuery = """
					select count(*)
					from incident i
					where i.org_id = :orgId
					  and (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					  and (
					      to_tsvector('english', coalesce(i.title, ''))
					          @@ websearch_to_tsquery('english', :query)
					      or to_tsvector('english', coalesce(i.description, ''))
					          @@ websearch_to_tsquery('english', :query)
					      or exists (
					          select 1
					          from timeline_entry te
					          where te.incident_id = i.id
					            and te.org_id = :orgId
					            and to_tsvector('english', coalesce(te.details, ''))
					                @@ websearch_to_tsquery('english', :query)
					      )
					  )
					""",
			nativeQuery = true)
	Page<IncidentSearchRow> search(
			@Param("query") String query,
			@Param("orgId") long orgId,
			@Param("serviceId") Long serviceId,
			@Param("severity") String severity,
			@Param("status") String status,
			Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select i from Incident i
			where i.service.id = :serviceId
			  and i.correlationSource = :source
			  and i.correlationKey = :correlationKey
			  and i.status not in :terminalStatuses
			order by i.createdAt asc, i.id asc
			""")
	List<Incident> findActiveByCorrelationForUpdate(
			@Param("serviceId") Long serviceId,
			@Param("source") String source,
			@Param("correlationKey") String correlationKey,
			@Param("terminalStatuses") List<IncidentStatus> terminalStatuses);
}
