package com.group2.incident.incident.service;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class IncidentEmbeddingService {

	private static final int RETRY_BATCH_SIZE = 10;

	private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

	private final AiEmbeddingClient aiEmbeddingClient;

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int retryPendingEmbeddings() {
		if (!aiEmbeddingClient.isEnabled()) {
			return 0;
		}

		Instant now = clock.instant();
		Instant leaseUntil = now.plus(CLAIM_LEASE);
		List<PendingIncident> pending = transactionTemplate.execute(
				status -> claimPending(now, leaseUntil));
		if (pending == null) {
			throw new IllegalStateException("Embedding claim transaction returned no batch");
		}

		for (PendingIncident incident : pending) {
			Optional<List<Double>> embedding = aiEmbeddingClient.embedDocument(documentText(
					incident.title(), incident.description(), incident.serviceName()));
			if (embedding.isPresent()) {
				transactionTemplate.executeWithoutResult(
						status -> saveEmbedding(incident.id(), incident.orgId(), embedding.get()));
			}
			else {
				transactionTemplate.executeWithoutResult(
						status -> deferRetry(incident.id(), incident.orgId(), incident.attempts()));
			}
		}
		return pending.size();
	}

	private List<PendingIncident> claimPending(Instant now, Instant leaseUntil) {
		List<PendingIncident> pending = jdbcTemplate.query("""
				select i.id, i.org_id, i.title, i.description, s.name as service_name, i.embedding_attempts
				from incident i
				join service s on s.id = i.service_id and s.org_id = i.org_id
				where i.embedding is null
				  and i.embedding_retry_at <= ?
				order by i.embedding_retry_at, i.id
				limit ?
				for update of i skip locked
				""", this::mapPendingIncident, Timestamp.from(now), RETRY_BATCH_SIZE);

		for (PendingIncident incident : pending) {
			int updated = jdbcTemplate.update("""
					update incident
					set embedding_retry_at = ?
					where id = ? and org_id = ? and embedding is null
					""", Timestamp.from(leaseUntil), incident.id(), incident.orgId());
			if (updated != 1) {
				throw new IllegalStateException("Could not claim embedding retry for incident " + incident.id());
			}
		}
		return pending;
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
