package com.settle.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.settle.investigation.dto.DraftKind;
import com.settle.investigation.service.AiDraftClient;
import com.settle.investigation.service.AiDraftClient.AiDraftResult;
import com.settle.support.IntegrationTest;
import com.settle.support.TestOrgs;
import com.settle.support.TestOrgs.SignedUp;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DraftTransactionTest {

	@Autowired private TestOrgs testOrgs;
	@Autowired private MockMvc mvc;
	@MockitoBean private AiDraftClient aiDraftClient;

	private SignedUp org;
	private long incidentId;

	@BeforeAll
	void createOrgAndIncident() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		org = testOrgs.signUp("draft-trans-" + suffix + "@test.local", "Draft trans " + suffix);
		long serviceId = testOrgs.createService(org.adminToken(), "DraftTransSvc", org.adminId());
		incidentId = TestOrgs.id(testOrgs.declareIncident(org.adminToken(), serviceId, "Draft transaction incident"));
	}

	@AfterAll
	void deleteOrg() {
		if (org != null) {
			testOrgs.delete(org.orgId());
		}
	}

	@BeforeEach
	void stubAi() {
		doAnswer(invocation -> true).when(aiDraftClient).isEnabled();
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new AiDraftResult(true, DraftKind.SUMMARY, "Draft summary", null);
		}).when(aiDraftClient).generateDraft(any());
	}

	@Test
	void draftingCallsAiOutsideDatabaseTransaction() throws Exception {
		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void disabledAiReturns503OnlyAfterIncidentLookup() throws Exception {
		doReturn(false).when(aiDraftClient).isEnabled();
		mvc.perform(post("/api/incidents/{id}/drafts", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isServiceUnavailable());
		mvc.perform(post("/api/incidents/{id}/drafts", Long.MAX_VALUE)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"SUMMARY\"}"))
				.andExpect(status().isNotFound());
		verify(aiDraftClient, never()).generateDraft(any());
	}
}
