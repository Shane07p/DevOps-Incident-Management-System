package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class CommentConcurrencyTest {

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	@Autowired
	private TestAccounts accounts;

	/** Posts two comments concurrently to PostgreSQL and checks persistence and version stability. */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void twoConcurrentCommentsArePersistedWithoutChangingIncidentVersion() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		List<Long> userIds = new ArrayList<>();
		Long serviceId = null;
		Long incidentId = null;
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Instant now = clock.instant();
			AppUser firstAuthor = accounts.save(new AppUser(
					"comment_a_" + suffix + "@test.local", "Comment Author A", "{noop}password", now), Role.ENGINEER);
			userIds.add(firstAuthor.getId());
			AppUser secondAuthor = accounts.save(new AppUser(
					"comment_b_" + suffix + "@test.local", "Comment Author B", "{noop}password", now), Role.ADMIN);
			userIds.add(secondAuthor.getId());

			ServiceEntity service = new ServiceEntity(
					"CommentService_" + suffix, "Comment concurrency test", firstAuthor, Criticality.HIGH, now);
			for (Severity severity : Severity.values()) {
				service.putTarget(severity, 60, 480);
			}
			service = services.saveAndFlush(service);
			serviceId = service.getId();

			Incident incident = new Incident(
					"Concurrent comments",
					"Test incident",
					service,
					Severity.SEV1,
					IncidentStatus.OPEN,
					firstAuthor,
					now,
					60,
					480
			);
			incident.assignNumber(1L);
			incident = incidents.saveAndFlush(incident);
			incidentId = incident.getId();
			Long originalVersion = incident.getVersion();
			Long testIncidentId = incidentId;
			Long firstAuthorId = firstAuthor.getId();
			Long secondAuthorId = secondAuthor.getId();
			long orgId = OrgContext.current();

			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			Future<TimelineEntryResponse> first = executor.submit(
					() -> OrgContext.runAs(orgId,
							() -> addCommentAtTheSameTime(ready, start, testIncidentId, firstAuthorId, "First comment")));
			Future<TimelineEntryResponse> second = executor.submit(
					() -> OrgContext.runAs(orgId,
							() -> addCommentAtTheSameTime(ready, start, testIncidentId, secondAuthorId, "Second comment")));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			TimelineEntryResponse firstResponse = first.get(10, TimeUnit.SECONDS);
			TimelineEntryResponse secondResponse = second.get(10, TimeUnit.SECONDS);

			assertThat(List.of(firstResponse.id(), secondResponse.id())).doesNotHaveDuplicates();
			assertThat(timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId))
					.filteredOn(entry -> entry.getEventType() == TimelineEventType.COMMENT)
					.extracting(TimelineEntry::getDetails)
					.containsExactlyInAnyOrder("First comment", "Second comment");
			assertThat(incidents.findById(incidentId).orElseThrow().getVersion()).isEqualTo(originalVersion);
		}
		finally {
			executor.shutdownNow();
			cleanup(incidentId, serviceId, userIds);
		}
	}

	/** Releases each worker to submit its comment together with the other worker. */
	private TimelineEntryResponse addCommentAtTheSameTime(
			CountDownLatch ready,
			CountDownLatch start,
			Long incidentId,
			Long actorId,
			String comment
) {
		ready.countDown();
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting to start concurrent comment submissions");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to start concurrent comment submissions", exception);
		}
		return incidentService.addComment(incidentId, comment, actorId);
	}

	/** Deletes the independently committed test rows and restores the append-only trigger. */
	private void cleanup(Long incidentId, Long serviceId, List<Long> userIds) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.executeWithoutResult(status -> {
			jdbcTemplate.execute("SET LOCAL session_replication_role = 'replica'");
			if (incidentId != null) {
				jdbcTemplate.update("DELETE FROM timeline_entry WHERE incident_id = ?", incidentId);
				incidents.deleteById(incidentId);
			}
			if (serviceId != null) {
				services.deleteById(serviceId);
			}
			for (Long userId : userIds) {
				jdbcTemplate.update("DELETE FROM membership WHERE user_id = ?", userId);
				users.deleteById(userId);
			}
			jdbcTemplate.execute("SET LOCAL session_replication_role = 'origin'");
		});
	}
}
