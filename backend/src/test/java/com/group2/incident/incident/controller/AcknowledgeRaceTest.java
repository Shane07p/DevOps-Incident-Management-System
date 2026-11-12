package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.jayway.jsonpath.JsonPath;
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
 * Two competing acknowledgements on the same incident at the same moment: one succeeds (200),
 * the second fails with optimistic lock conflict (409), and database consistency is preserved.
 * Outside the test transaction, against PostgreSQL, following ReassignRaceTest pattern.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AcknowledgeRaceTest {

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

	private SignedUp raceOrg;

	@AfterEach
	void cleanUp() {
		competingThread.shutdownNow();
		if (raceOrg != null) {
			orgs.delete(raceOrg.orgId());
		}
	}

	@Test
	@DisplayName("TC-US-08c: Concurrency - Two people acknowledge at the same moment: one gets 200, the other gets 409")
	void tcUs08c_twoPeopleAcknowledgeAtSameMomentOneWinsOtherGets409() throws Exception {
		raceOrg = orgs.signUp("ack-race-admin@race.test", "Ack Race Org");
		long adminId = raceOrg.adminId();
		String adminToken = raceOrg.adminToken();

		long eng1Id = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ack-eng1@race.test\",\"displayName\":\"Engineer One\",\"password\":\"DevPassw0rd!\",\"role\":\"ENGINEER\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		long eng2Id = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ack-eng2@race.test\",\"displayName\":\"Engineer Two\",\"password\":\"DevPassw0rd!\",\"role\":\"ENGINEER\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		String eng1Token = JsonPath.read(mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ack-eng1@race.test\",\"password\":\"DevPassw0rd!\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.token");

		String eng2Token = JsonPath.read(mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ack-eng2@race.test\",\"password\":\"DevPassw0rd!\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.token");

		long serviceId = orgs.createService(adminToken, "Ack Race Service", adminId);
		long incidentId = TestOrgs.id(orgs.declareIncident(adminToken, serviceId, "Ack Race Incident"));

		long seen = jdbc.queryForObject("SELECT version FROM incident WHERE id = ?", Long.class, incidentId);
		CountDownLatch secondStarted = new CountDownLatch(1);

		Future<MvcResult> secondAttempt = OrgContext.runAs(raceOrg.orgId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
			try {
				MvcResult firstResult = mvc.perform(post("/api/incidents/" + incidentId + "/acknowledge")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + eng1Token)
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"expectedVersion\": " + seen + "}"))
						.andExpect(status().isOk())
						.andReturn();
				assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);

				Future<MvcResult> started = competingThread.submit(() -> {
					secondStarted.countDown();
					return mvc.perform(post("/api/incidents/" + incidentId + "/acknowledge")
									.header(HttpHeaders.AUTHORIZATION, "Bearer " + eng2Token)
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"expectedVersion\": " + seen + "}"))
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

		Incident incident = OrgContext.runAs(raceOrg.orgId(),
				() -> incidentRepository.findById(incidentId).orElseThrow());
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getAssignee().getId()).isEqualTo(eng1Id);
		assertThat(incident.getVersion()).isEqualTo(seen + 1);

		List<TimelineEntry> ackEntries = OrgContext.runAs(raceOrg.orgId(),
				() -> timelineEntryRepository.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incidentId))
				.stream()
				.filter(entry -> entry.getEventType() == TimelineEventType.ACKNOWLEDGED)
				.toList();
		assertThat(ackEntries).hasSize(1);
		assertThat(ackEntries.get(0).getActor().getId()).isEqualTo(eng1Id);
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
