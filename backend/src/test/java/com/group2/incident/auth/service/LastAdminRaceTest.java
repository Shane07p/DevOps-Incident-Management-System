package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.dto.ChangeEnabledRequest;
import com.group2.incident.auth.dto.ChangeRoleRequest;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two Admins demote each other at the same moment: the second must wait for the first and then be refused,
 * so the org keeps an Admin (FR-01.6). Outside the test transaction, against PostgreSQL.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LastAdminRaceTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private UserAdminService userAdminService;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final ExecutorService otherAdmin = Executors.newSingleThreadExecutor();

	private SignedUp org;

	@AfterEach
	void cleanUp() {
		otherAdmin.shutdownNow();
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@Test
	void twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
		org = orgs.signUp("race-first@race.test", "Race Org");
		long first = org.adminId();
		long second = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"race-second@race.test\",\"displayName\":\"Second\",\"password\":\"DevPassw0rd!\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		// The first demotion holds its transaction open while the second starts on another thread.
		Future<UserResponse> secondDemotion = OrgContext.runAs(org.orgId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
			userAdminService.changeRole(second, new ChangeRoleRequest(Role.ENGINEER), first);
			Future<UserResponse> started = otherAdmin.submit(() ->
					OrgContext.runAs(org.orgId(), () -> userAdminService.changeRole(first, new ChangeRoleRequest(Role.ENGINEER), second)));
			waitForASessionBlockedOnALock();
			return started;
		}));

		assertThatThrownBy(() -> secondDemotion.get(30, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(ConflictException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM membership WHERE org_id = ? AND role = 'ADMIN' AND enabled",
				Long.class, org.orgId())).isEqualTo(1);
	}

	// TC-FR-01.6h: two Admins disable each other at the same moment; the second waits, then gets 409.
	@Test
	void twoAdminsDisablingEachOtherAtOnceLeaveOneEnabledAdmin() throws Exception {
		org = orgs.signUp("race-off-first@race.test", "Race Off Org");
		long first = org.adminId();
		long second = TestOrgs.id(mvc.perform(post("/api/users")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"race-off-second@race.test\",\"displayName\":\"Second\",\"password\":\"DevPassw0rd!\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		Future<UserResponse> secondDisable = OrgContext.runAs(org.orgId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
			userAdminService.changeEnabled(second, new ChangeEnabledRequest(false), first);
			Future<UserResponse> started = otherAdmin.submit(() ->
					OrgContext.runAs(org.orgId(), () -> userAdminService.changeEnabled(first, new ChangeEnabledRequest(false), second)));
			waitForASessionBlockedOnALock();
			return started;
		}));

		assertThatThrownBy(() -> secondDisable.get(30, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(ConflictException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM membership WHERE org_id = ? AND role = 'ADMIN' AND enabled",
				Long.class, org.orgId())).isEqualTo(1);
	}

	// Gives up after two seconds: without the org lock the second demotion never blocks.
	private void waitForASessionBlockedOnALock() {
		for (int i = 0; i < 40; i++) {
			Integer blocked = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
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
	}

}
