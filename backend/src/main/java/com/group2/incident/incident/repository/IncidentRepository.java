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

	// The list shows the service name and the Lead (the assignee); the reporter stays unloaded.
	@EntityGraph(attributePaths = { "service", "assignee" })
	List<Incident> findAllByOrderByCreatedAtDescIdDesc();

	@EntityGraph(attributePaths = { "service", "reporter", "assignee", "comms", "ops" })
	Optional<Incident> findWithDetailsById(Long id);

	/**
	 * Searches incident and timeline text through separate full-text-indexed branches.
	 *
	 * @param query PostgreSQL web-search query
	 * @param orgId organization whose incidents may be returned
	 * @param serviceId optional service filter
	 * @param severity optional severity filter
	 * @param status optional incident-status filter
	 * @param pageable page request, limited by the caller to the search page size
	 * @return matching incidents and their matched-field flags
	 */
	@Query(
			value = """
					with matches as (
					    select i.id as incident_id, 'title' as matched_field
					    from incident i
					    where i.org_id = :orgId
					      and to_tsvector('english', coalesce(i.title, ''))
					          @@ websearch_to_tsquery('english', :query)
					    union all
					    select i.id as incident_id, 'description' as matched_field
					    from incident i
					    where i.org_id = :orgId
					      and to_tsvector('english', coalesce(i.description, ''))
					          @@ websearch_to_tsquery('english', :query)
					    union all
					    select te.incident_id, 'timeline' as matched_field
					    from timeline_entry te
					    where te.org_id = :orgId
					      and to_tsvector('english', coalesce(te.details, ''))
					          @@ websearch_to_tsquery('english', :query)
					),
					matched_incidents as (
					    select incident_id,
					           bool_or(matched_field = 'title') as title_match,
					           bool_or(matched_field = 'description') as description_match,
					           bool_or(matched_field = 'timeline') as timeline_match
					    from matches
					    group by incident_id
					)
					select i.id as "incidentId",
					       i.number as "incidentNumber",
					       i.service_id as "serviceId",
					       i.title as "title",
					       s.name as "serviceName",
					       i.severity as "severity",
					       i.status as "status",
					       i.created_at as "createdAt",
					       m.title_match as "titleMatch",
					       m.description_match as "descriptionMatch",
					       m.timeline_match as "timelineMatch"
					from matched_incidents m
					join incident i on i.id = m.incident_id and i.org_id = :orgId
					join service s on s.id = i.service_id and s.org_id = :orgId
					where (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					order by i.created_at desc, i.id desc
					""",
			countQuery = """
					with matches as (
					    select i.id as incident_id
					    from incident i
					    where i.org_id = :orgId
					      and to_tsvector('english', coalesce(i.title, ''))
					          @@ websearch_to_tsquery('english', :query)
					    union
					    select i.id as incident_id
					    from incident i
					    where i.org_id = :orgId
					      and to_tsvector('english', coalesce(i.description, ''))
					          @@ websearch_to_tsquery('english', :query)
					    union
					    select te.incident_id
					    from timeline_entry te
					    where te.org_id = :orgId
					      and to_tsvector('english', coalesce(te.details, ''))
					          @@ websearch_to_tsquery('english', :query)
					)
					select count(*)
					from matches m
					join incident i on i.id = m.incident_id and i.org_id = :orgId
					join service s on s.id = i.service_id and s.org_id = :orgId
					where (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					""",
			nativeQuery = true)
	Page<IncidentSearchRow> search(
			@Param("query") String query,
			@Param("orgId") long orgId,
			@Param("serviceId") Long serviceId,
			@Param("severity") String severity,
			@Param("status") String status,
			Pageable pageable);

	/**
	 * Lists incidents in one organization without a text filter, newest first.
	 *
	 * @param orgId organization whose incidents may be returned
	 * @param serviceId optional service filter
	 * @param severity optional severity filter
	 * @param status optional incident-status filter
	 * @param pageable page request, limited by the caller to the search page size
	 * @return matching incidents with no matched text fields
	 */
	@Query(
			value = """
					select i.id as "incidentId",
					       i.number as "incidentNumber",
					       i.service_id as "serviceId",
					       i.title as "title",
					       s.name as "serviceName",
					       i.severity as "severity",
					       i.status as "status",
					       i.created_at as "createdAt",
					       false as "titleMatch",
					       false as "descriptionMatch",
					       false as "timelineMatch"
					from incident i
					join service s on s.id = i.service_id and s.org_id = :orgId
					where i.org_id = :orgId
					  and (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					order by i.created_at desc, i.id desc
					""",
			countQuery = """
					select count(*)
					from incident i
					join service s on s.id = i.service_id and s.org_id = :orgId
					where i.org_id = :orgId
					  and (:serviceId is null or i.service_id = :serviceId)
					  and (cast(:severity as varchar) is null or i.severity = cast(:severity as varchar))
					  and (cast(:status as varchar) is null or i.status = cast(:status as varchar))
					""",
			nativeQuery = true)
	Page<IncidentSearchRow> searchWithoutKeyword(
			@Param("orgId") long orgId,
			@Param("serviceId") Long serviceId,
			@Param("severity") String severity,
			@Param("status") String status,
			Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Incident i where i.id = :id")
	Optional<Incident> findByIdForUpdate(@Param("id") Long id);

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
