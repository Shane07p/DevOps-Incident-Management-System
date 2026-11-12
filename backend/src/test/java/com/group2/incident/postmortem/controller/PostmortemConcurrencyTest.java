package com.group2.incident.postmortem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Real overlapping writes: the unique incident key and @Version each reject a losing request. */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostmortemConcurrencyTest {

	@Autowired private TestOrgs orgs;
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;

	private final ExecutorService contender = Executors.newSingleThreadExecutor();
	private SignedUp org;

	@AfterEach
	void cleanUp() {
		contender.shutdownNow();
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@ParameterizedTest(name = "existing draft: {0}")
	@ValueSource(booleans = { false, true })
	void overlappingCreatesAndEditsKeepOnlyTheWinningDraft(boolean existingDraft) throws Exception {
		org = orgs.signUp("pm-race@test.local", "Postmortem race");
		long serviceId = orgs.createService(org.adminToken(), "Payments", org.adminId());
		long incidentId = TestOrgs.id(orgs.declareIncident(org.adminToken(), serviceId, "Checkout failing"));
		String path = "/api/incidents/" + incidentId + "/postmortem";
		if (existingDraft) {
			write(path, "{\"summary\":\"Original\"}").andExpect(status().isOk());
		}
		String version = existingDraft ? "0" : "null";
		Future<MvcResult> losing = OrgContext.runAs(org.orgId(), () -> new TransactionTemplate(transactions).execute(tx -> {
			try {
				write(path, "{\"summary\":\"Winner\",\"expectedVersion\":" + version + "}").andExpect(status().isOk());
				Future<MvcResult> pending = contender.submit(() -> write(path,
						"{\"summary\":\"Loser\",\"expectedVersion\":" + version + "}").andReturn());
				awaitPostmortemLock(pending);
				return pending;
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}));
		MvcResult result = losing.get(30, TimeUnit.SECONDS);
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(result.getResponse().getContentAsString()).contains("This record was changed by another request");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM postmortem WHERE incident_id = ? AND org_id = ?",
				Integer.class, incidentId, org.orgId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT summary FROM postmortem WHERE incident_id = ? AND org_id = ?",
				String.class, incidentId, org.orgId())).isEqualTo("Winner");
		assertThat(jdbc.queryForObject("SELECT version FROM postmortem WHERE incident_id = ? AND org_id = ?",
				Long.class, incidentId, org.orgId())).isEqualTo(existingDraft ? 1L : 0L);
	}

	private org.springframework.test.web.servlet.ResultActions write(String path, String body) throws Exception {
		return mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void awaitPostmortemLock(Future<MvcResult> pending) throws Exception {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (pending.isDone()) {
				throw new IllegalStateException("The competing write returned before the winner committed: "
						+ pending.get().getResponse().getStatus());
			}
			// pg_stat_activity otherwise reuses the first statistics snapshot in this transaction.
			jdbc.execute("SELECT pg_stat_clear_snapshot()");
			Integer blocked = jdbc.queryForObject("""
					SELECT count(*) FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND query LIKE '%postmortem%'
					""", Integer.class);
			if (blocked != null && blocked > 0) {
				return;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("The competing postmortem write did not reach its database lock");
	}
}
