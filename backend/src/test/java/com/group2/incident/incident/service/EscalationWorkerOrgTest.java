package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled worker has no request and no org: it reads overdue tasks across every org and runs each in
 * its own org (D48). Outside the test transaction, as on the scheduler's thread.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscalationWorkerOrgTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private EscalationWorker escalationWorker;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	private SignedUp acme;

	private SignedUp globex;

	@AfterEach
	void deleteOrgs() {
		if (acme != null) {
			orgs.delete(acme.orgId());
		}
		if (globex != null) {
			orgs.delete(globex.orgId());
		}
	}

	@Test
	void overdueTasksInTwoOrgsEachEscalateInTheirOwnOrg() throws Exception {
		acme = orgs.signUp("worker-acme@acme.test", "Worker Acme");
		globex = orgs.signUp("worker-globex@globex.test", "Worker Globex");
		declareWithAPolicy(acme);
		declareWithAPolicy(globex);

		// A scheduler thread has no org; the worker must not depend on one.
		int processed = OrgContext.runAs(OrgContext.NONE, () -> escalationWorker.runOnce(clock.instant().plus(Duration.ofHours(1))));

		assertThat(processed).isGreaterThanOrEqualTo(2);
		assertThat(escalationsIn(acme)).isEqualTo(1);
		assertThat(escalationsIn(globex)).isEqualTo(1);
	}

	private void declareWithAPolicy(SignedUp org) throws Exception {
		long service = orgs.createService(org.adminToken(), "Payments", org.adminId());
		mvc.perform(put("/api/services/{id}/escalation-policy", service)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"steps\": [{\"stepNumber\": 1, \"responderId\": %d, \"waitMinutes\": 15}]}".formatted(org.adminId())))
				.andExpect(status().isOk());
		orgs.declareIncident(org.adminToken(), service, "Checkout failing");
	}

	private long escalationsIn(SignedUp org) {
		return jdbc.queryForObject("SELECT count(*) FROM timeline_entry WHERE org_id = ? AND event_type = 'ESCALATED'", Long.class,
				org.orgId());
	}

}
