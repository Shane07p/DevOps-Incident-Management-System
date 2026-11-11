package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.incident.service.AiEmbeddingClient;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentSimilarityTransactionTest {

	@Autowired
	private TestOrgs testOrgs;

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private AiEmbeddingClient aiEmbeddingClient;

	private SignedUp org;

	@BeforeAll
	void createOrg() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		org = testOrgs.signUp("similarity-transaction-" + suffix + "@test.local", "Similarity transaction " + suffix);
	}

	@AfterAll
	void deleteOrg() {
		if (org != null) {
			testOrgs.delete(org.orgId());
		}
	}

	@BeforeEach
	void enableAi() {
		doAnswer(invocation -> true).when(aiEmbeddingClient).isEnabled();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return Optional.of(vector());
		}).when(aiEmbeddingClient).embedQuery(anyString());
	}

	@Test
	void duplicateCheckEmbedsOutsideDatabaseTransaction() throws Exception {
		mvc.perform(get("/api/incidents/duplicate-check")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.queryParam("title", "Checkout timeout"))
				.andExpect(status().isOk());
	}

	@Test
	void semanticSearchEmbedsOutsideDatabaseTransaction() throws Exception {
		mvc.perform(get("/api/incidents/search")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.queryParam("q", "checkout timeout")
						.queryParam("mode", "semantic"))
				.andExpect(status().isOk());
	}

	private static List<Double> vector() {
		List<Double> values = new ArrayList<>(768);
		values.add(1.0);
		for (int index = 1; index < 768; index++) {
			values.add(0.0);
		}
		return values;
	}
}
