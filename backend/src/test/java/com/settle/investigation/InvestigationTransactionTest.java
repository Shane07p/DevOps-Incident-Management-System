package com.settle.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.settle.investigation.service.AiInvestigationClient;
import com.settle.investigation.service.AiInvestigationClient.AiEvidenceItem;
import com.settle.investigation.service.AiInvestigationClient.AiHypothesis;
import com.settle.investigation.service.AiInvestigationClient.AiInvestigationResult;
import com.settle.support.IntegrationTest;
import com.settle.support.TestOrgs;
import com.settle.support.TestOrgs.SignedUp;
import java.util.List;
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
class InvestigationTransactionTest {

	@Autowired
	private TestOrgs testOrgs;

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private AiInvestigationClient aiInvestigationClient;

	private SignedUp org;
	private long incidentId;

	@BeforeAll
	void createOrgAndIncident() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		org = testOrgs.signUp("inv-trans-" + suffix + "@test.local", "Inv trans " + suffix);
		long serviceId = testOrgs.createService(org.adminToken(), "InvTransSvc", org.adminId());
		String incJson = testOrgs.declareIncident(org.adminToken(), serviceId, "Trans Incident");
		incidentId = TestOrgs.id(incJson);
	}

	@AfterAll
	void deleteOrg() {
		if (org != null) {
			testOrgs.delete(org.orgId());
		}
	}

	@BeforeEach
	void stubAi() {
		doAnswer(invocation -> true).when(aiInvestigationClient).isEnabled();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new AiInvestigationResult(
					true,
					"completed",
					"Test investigation summary",
					List.of(new AiHypothesis("Hypothesis 1", 0.9, List.of("ev-1"), "reason")),
					List.of(new AiEvidenceItem("ev-1", "test_tool", "log", "sample evidence")),
					List.of("Suggested action 1"),
					1,
					50,
					10.0
			);
		}).when(aiInvestigationClient).investigate(any());
	}

	@Test
	void investigationCallsAiOutsideDatabaseTransaction() throws Exception {
		mvc.perform(post("/api/incidents/{id}/investigations", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken()))
				.andExpect(status().isCreated());
	}
}
