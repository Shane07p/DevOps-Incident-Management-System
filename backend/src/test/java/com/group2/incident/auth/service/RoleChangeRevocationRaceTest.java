package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.dto.ChangeRoleRequest;
import com.group2.incident.auth.dto.CreateUserRequest;
import com.group2.incident.auth.dto.LoginRequest;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A role change that read the user before two logouts committed must not write its older token version
 * back, or the token the second logout revoked would work again (D21). Outside the test transaction,
 * against PostgreSQL.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoleChangeRevocationRaceTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private UserAdminService admins;

	@Autowired
	private AuthService auth;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private MockMvc mvc;

	private final ExecutorService roleChangeThread = Executors.newSingleThreadExecutor();

	private SignedUp org;

	@AfterEach
	void cleanUp() {
		roleChangeThread.shutdownNow();
		if (org != null) {
			orgs.delete(org.orgId());
		}
	}

	@Test
	void roleChangeDoesNotRestoreATokenRevokedByALogoutMeanwhile() throws Exception {
		org = orgs.signUp("revocation-race-admin@race.test", "Revocation Race Org");
		String email = "revocation-race-person@race.test";
		long person = OrgContext.runAs(org.orgId(), () -> admins.createUser(
				new CreateUserRequest(email, "Race Person", TestOrgs.PASSWORD, Role.ENGINEER), org.adminId())).id();

		CountDownLatch userRead = new CountDownLatch(1);
		CountDownLatch carryOn = new CountDownLatch(1);
		Future<?> roleChange = roleChangeThread.submit(() -> OrgContext.runAs(org.orgId(), () ->
				new TransactionTemplate(transactionManager).execute(tx -> {
					// The role change's own copy of the user, still at token version 0.
					users.findById(person).orElseThrow().getTokenVersion();
					userRead.countDown();
					await(carryOn);
					admins.changeRole(person, new ChangeRoleRequest(Role.VIEWER), org.adminId());
					return null;
				})));
		String revoked;
		try {
			assertThat(userRead.await(10, TimeUnit.SECONDS)).isTrue();
			auth.logout(person);
			revoked = auth.login(new LoginRequest(email, TestOrgs.PASSWORD)).token();
			auth.logout(person);
		}
		finally {
			carryOn.countDown();
		}
		roleChange.get(15, TimeUnit.SECONDS);

		// Two logouts and the role change: three increments, none lost.
		assertThat(users.findById(person).orElseThrow().getTokenVersion()).isEqualTo(3);
		mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + revoked))
				.andExpect(status().isUnauthorized());
		String token = auth.login(new LoginRequest(email, TestOrgs.PASSWORD)).token();
		mvc.perform(get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the logouts");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
