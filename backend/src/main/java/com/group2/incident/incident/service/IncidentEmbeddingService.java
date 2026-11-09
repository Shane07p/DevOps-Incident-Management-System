package com.group2.incident.incident.service;

import com.group2.incident.incident.entity.Incident;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentEmbeddingService {

	private static final int RETRY_BATCH_SIZE = 10;

	private final AiEmbeddingClient aiEmbeddingClient;

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	public void embedOnCreation(Incident incident) {
		if (!aiEmbeddingClient.isEnabled()) {
			return;
		}

		Optional<List<Double>> embedding = aiEmbeddingClient.embedDocument(documentText(
				incident.getTitle(), incident.getDescription(), incident.getService().getName()));
		if (embedding.isPresent()) {
			saveEmbedding(incident.getId(), incident.getOrgId(), embedding.get());
		}
		else {
			deferRetry(incident.getId(), incident.getOrgId(), 0);
		}
	}

	@Transactional
	public int retryPendingEmbeddings() {
		if (!aiEmbeddingClient.isEnabled()) {
			return 0;
		}

		List<PendingIncident> pending = jdbcTemplate.query("""
				select i.id, i.org_id, i.title, i.description, s.name as service_name, i.embedding_attempts
				from incident i
				join service s on s.id = i.service_id and s.org_id = i.org_id
				where i.embedding is null
				  and i.embedding_retry_at <= ?
				order by i.embedding_retry_at, i.id
				limit ?
				for update of i skip locked
				""", this::mapPendingIncident, Timestamp.from(clock.instant()), RETRY_BATCH_SIZE);

		for (PendingIncident incident : pending) {
			Optional<List<Double>> embedding = aiEmbeddingClient.embedDocument(documentText(
					incident.title(), incident.description(), incident.serviceName()));
			if (embedding.isPresent()) {
				saveEmbedding(incident.id(), incident.orgId(), embedding.get());
			}
			else {
				deferRetry(incident.id(), incident.orgId(), incident.attempts());
			}
		}
		return pending.size();
	}

	private void saveEmbedding(long incidentId, long orgId, List<Double> embedding) {
		String vector = embedding.stream()
				.map(value -> Double.toString(value))
				.collect(Collectors.joining(",", "[", "]"));
		int updated = jdbcTemplate.update("""
				update incident
				set embedding = cast(? as incident.vector),
				    embedding_attempts = 0,
				    embedding_retry_at = null
				where id = ? and org_id = ? and embedding is null
				""", vector, incidentId, orgId);
		if (updated != 1) {
			throw new IllegalStateException("Could not save embedding for incident " + incidentId);
		}
	}

	private void deferRetry(long incidentId, long orgId, int attempts) {
		int nextAttempts = attempts + 1;
		long delayMinutes = 1L << Math.min(nextAttempts - 1, 5);
		Instant retryAt = clock.instant().plus(Duration.ofMinutes(delayMinutes));
		int updated = jdbcTemplate.update("""
				update incident
				set embedding_attempts = ?,
				    embedding_retry_at = ?
				where id = ? and org_id = ? and embedding is null
				""", nextAttempts, Timestamp.from(retryAt), incidentId, orgId);
		if (updated != 1) {
			throw new IllegalStateException("Could not schedule embedding retry for incident " + incidentId);
		}
	}

	private PendingIncident mapPendingIncident(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PendingIncident(
				resultSet.getLong("id"),
				resultSet.getLong("org_id"),
				resultSet.getString("title"),
				resultSet.getString("description"),
				resultSet.getString("service_name"),
				resultSet.getInt("embedding_attempts"));
	}

	private static String documentText(String title, String description, String serviceName) {
		return """
				Title: %s
				Symptoms: %s
				Service: %s
				""".formatted(title, description, serviceName);
	}

	private record PendingIncident(long id, long orgId, String title, String description, String serviceName, int attempts) {
	}
}
