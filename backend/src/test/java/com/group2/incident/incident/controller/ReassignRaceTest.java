package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two competing reassignments on the same incident at the same moment: one succeeds (200),
 * the second fails with optimistic lock conflict (409), and database consistency is preserved.
 * Outside the test transaction, against PostgreSQL, following LastAdminRaceTest pattern.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReassignRaceTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private TimelineEntryRepository timelineEntryRepository;

	private final ExecutorService competingThread = Executors.newSingleThreadExecutor();

	private SignedUp org;

	@AfterEach
	void cleanUp() {
		competingThread.shutdownNow();
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@Test
	@DisplayName("Two concurrent reassignments on the same incident: one succeeds (200), other receives 409, and database remains consistent")
	void twoConcurrentReassignmentsResultInOneWinnerAndOneConflict() throws Exception {
		org = orgs.signUp("race-admin@race.test", "Race Org");
		long adminId = org.adminId();
		String adminToken = org.adminToken();

		long firstAssignee = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"race-eng1@race.test\",\"displayName\":\"Engineer One\",\"password\":\"DevPassw0rd!\",\"role\":\"ENGINEER\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		long secondAssignee = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"race-eng2@race.test\",\"displayName\":\"Engineer Two\",\"password\":\"DevPassw0rd!\",\"role\":\"ENGINEER\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		long serviceId = orgs.createService(adminToken, "Race Service", adminId);
		long incidentId = TestOrgs.id(orgs.declareIncident(adminToken, serviceId, "Race Incident"));

		// Both requests come from screens that loaded the incident at this version.
		long seen = jdbc.queryForObject("SELECT version FROM incident WHERE id = ?", Long.class, incidentId);
		CountDownLatch secondStarted = new CountDownLatch(1);

		// The first reassignment holds its transaction open after flushing its UPDATE so the second blocks on the row lock.
		Future<MvcResult> secondAttempt = OrgContext.runAs(org.orgId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
			try {
				MvcResult firstResult = mvc.perform(put("/api/incidents/" + incidentId + "/assignee")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"assigneeId\": " + firstAssignee + ", \"expectedVersion\": " + seen + "}"))
						.andExpect(status().isOk())
						.andReturn();
				assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);

				Future<MvcResult> started = competingThread.submit(() -> {
					secondStarted.countDown();
					return mvc.perform(put("/api/incidents/" + incidentId + "/assignee")
									.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"assigneeId\": " + secondAssignee + ", \"expectedVersion\": " + seen + "}"))
							.andReturn();
				});

				assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
				waitForASessionBlockedOnALock();
				return started;
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));

		MvcResult secondResult = secondAttempt.get(30, TimeUnit.SECONDS);

		assertThat(secondResult.getResponse().getStatus()).isEqualTo(409);
		assertThat(secondResult.getResponse().getContentAsString())
				.contains("This record was changed by another request; reload and try again");

		// Verify database consistency: Incident assignee is firstAssignee and version incremented once
		Incident persistedIncident = OrgContext.runAs(org.orgId(), () -> incidentRepository.findById(incidentId).orElseThrow());
		assertThat(persistedIncident.getAssignee().getId()).isEqualTo(firstAssignee);
		assertThat(persistedIncident.getVersion()).isEqualTo(1L);

		// Only the winning reassignment's timeline entry is persisted
		List<TimelineEntry> reassignedEntries = OrgContext.runAs(org.orgId(), () ->
				timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId)
						.stream()
						.filter(entry -> entry.getEventType() == TimelineEventType.REASSIGNED)
						.toList()
		);
		assertThat(reassignedEntries).hasSize(1);
		assertThat(reassignedEntries.get(0).getDetails()).contains("Engineer One");
		assertThat(reassignedEntries.get(0).getDetails()).doesNotContain("Engineer Two");

		// Only the winning reassignment's notification is persisted
		Long firstNotifications = jdbc.queryForObject(
				"SELECT count(*) FROM notification WHERE org_id = ? AND recipient_id = ?",
				Long.class, org.orgId(), firstAssignee
		);
		assertThat(firstNotifications).isEqualTo(1L);

		Long secondNotifications = jdbc.queryForObject(
				"SELECT count(*) FROM notification WHERE org_id = ? AND recipient_id = ?",
				Long.class, org.orgId(), secondAssignee
		);
		assertThat(secondNotifications).isEqualTo(0L);

		Long totalIncidentNotifications = jdbc.queryForObject(
				"SELECT count(*) FROM notification WHERE org_id = ? AND incident_id = ?",
				Long.class, org.orgId(), incidentId
		);
		assertThat(totalIncidentNotifications).isEqualTo(1L);
	}

	private void waitForASessionBlockedOnALock() {
		for (int i = 0; i < 40; i++) {
			Integer blocked = jdbc.queryForObject(
					"SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
					Integer.class
			);
			if (blocked != null && blocked > 0) {
				return;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new IllegalStateException("Timed out waiting for competing session to block on incident row lock");
	}

}

