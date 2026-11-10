package com.group2.incident.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrent duplicate token creation for the same user in the same org: one succeeds (201),
 * the other fails with conflict (409) due to database-level unique constraint (D38, #151).
 * Outside the test transaction, against PostgreSQL.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonalAccessTokenRaceTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private SignedUp org1;
	private SignedUp org2;

	@AfterEach
	void cleanUp() {
		if (org1 != null) {
			orgs.delete(org1.orgId());
		}
		if (org2 != null) {
			orgs.delete(org2.orgId());
		}
	}

	@Test
	void concurrentDuplicateCreation_oneWinsOneGets409() throws Exception {
		org1 = orgs.signUp("pat-race-" + System.currentTimeMillis() + "@race.test", "Race Org 1");
		String token = org1.adminToken();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);

			Future<MvcResult> req1 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/api/me/tokens")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"name\": \"ConcurrentCursor\"}"))
						.andReturn();
			});

			Future<MvcResult> req2 = executor.submit(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post("/api/me/tokens")
								.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"name\": \"concurrentcursor\"}"))
						.andReturn();
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			MvcResult res1 = req1.get(10, TimeUnit.SECONDS);
			MvcResult res2 = req2.get(10, TimeUnit.SECONDS);

			List<Integer> statuses = List.of(res1.getResponse().getStatus(), res2.getResponse().getStatus());
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);

			Integer count = jdbc.queryForObject(
					"SELECT count(*) FROM personal_access_token WHERE org_id = ? AND user_id = ? AND lower(name) = lower(?)",
					Integer.class, org1.orgId(), org1.adminId(), "ConcurrentCursor");
			assertThat(count).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void sameNameInDifferentOrgs_bothSucceed() throws Exception {
		org1 = orgs.signUp("pat-org1-" + System.currentTimeMillis() + "@race.test", "Race Org A");
		org2 = orgs.signUp("pat-org2-" + System.currentTimeMillis() + "@race.test", "Race Org B");

		MvcResult res1 = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org1.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"SharedTool\"}"))
				.andReturn();

		MvcResult res2 = mvc.perform(post("/api/me/tokens")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org2.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"sharedtool\"}"))
				.andReturn();

		assertThat(res1.getResponse().getStatus()).isEqualTo(201);
		assertThat(res2.getResponse().getStatus()).isEqualTo(201);
	}
}
