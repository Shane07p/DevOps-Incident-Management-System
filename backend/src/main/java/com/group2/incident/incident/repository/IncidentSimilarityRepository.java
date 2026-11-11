package com.group2.incident.incident.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IncidentSimilarityRepository {

	private static final String MATCH_FILTERS = """
			i.org_id = ?
			and i.embedding is not null
			and (cast(? as bigint) is null or i.id <> cast(? as bigint))
			and (cast(? as timestamptz) is null or i.created_at < cast(? as timestamptz))
			and (cast(? as bigint) is null or i.service_id = cast(? as bigint))
			and (cast(? as varchar) is null or i.severity = cast(? as varchar))
			and (cast(? as varchar) is null or i.status = cast(? as varchar))
			and (cast(? as boolean) = false or i.status not in ('RESOLVED', 'CLOSED'))
			and 1.0 - (i.embedding <=> q.vector) >= ?
			""";

	private static final String COUNT_MATCHES = """
			with q as (select cast(? as incident.vector) as vector)
			select count(*)
			from incident i
			join service s on s.id = i.service_id and s.org_id = i.org_id
			cross join q
			where %s
			""".formatted(MATCH_FILTERS);

	private static final String SELECT_MATCHES = """
			with q as (select cast(? as incident.vector) as vector)
			select i.id,
			       i.number,
			       i.service_id,
			       i.title,
			       s.name as service_name,
			       i.severity,
			       i.status,
			       i.created_at,
			       greatest(0.0, least(1.0, 1.0 - (i.embedding <=> q.vector))) as score
			from incident i
			join service s on s.id = i.service_id and s.org_id = i.org_id
			cross join q
			where %s
			order by i.embedding <=> q.vector, i.created_at desc, i.id desc
			limit ? offset ?
			""".formatted(MATCH_FILTERS);

	private final JdbcTemplate jdbcTemplate;

	public Optional<String> findEmbedding(long incidentId, long orgId) {
		List<String> embeddings = jdbcTemplate.query(
				"select embedding::text from incident where id = ? and org_id = ? and embedding is not null",
				(resultSet, rowNumber) -> resultSet.getString(1),
				incidentId, orgId);
		return embeddings.stream().findFirst();
	}

	public List<IncidentSimilarityMatch> findMatches(
			long orgId,
			String vector,
			Long excludedIncidentId,
			Instant createdBefore,
			Long serviceId,
			String severity,
			String status,
			boolean openOnly,
			double threshold,
			int limit,
			long offset
	) {
		return jdbcTemplate.query(SELECT_MATCHES, this::mapMatch,
				vector,
				orgId,
				excludedIncidentId, excludedIncidentId,
				createdBefore != null ? Timestamp.from(createdBefore) : null,
				createdBefore != null ? Timestamp.from(createdBefore) : null,
				serviceId, serviceId,
				severity, severity,
				status, status,
				openOnly,
				threshold,
				limit,
				offset);
	}

	public long countMatches(
			long orgId,
			String vector,
			Long excludedIncidentId,
			Instant createdBefore,
			Long serviceId,
			String severity,
			String status,
			boolean openOnly,
			double threshold
	) {
		Long count = jdbcTemplate.queryForObject(COUNT_MATCHES, Long.class,
				vector,
				orgId,
				excludedIncidentId, excludedIncidentId,
				createdBefore != null ? Timestamp.from(createdBefore) : null,
				createdBefore != null ? Timestamp.from(createdBefore) : null,
				serviceId, serviceId,
				severity, severity,
				status, status,
				openOnly,
				threshold);
		return count != null ? count : 0;
	}

	private IncidentSimilarityMatch mapMatch(ResultSet resultSet, int rowNumber) throws SQLException {
		return new IncidentSimilarityMatch(
				resultSet.getLong("id"),
				resultSet.getLong("number"),
				resultSet.getLong("service_id"),
				resultSet.getString("title"),
				resultSet.getString("service_name"),
				resultSet.getString("severity"),
				resultSet.getString("status"),
				resultSet.getTimestamp("created_at").toInstant(),
				resultSet.getDouble("score"));
	}
}
