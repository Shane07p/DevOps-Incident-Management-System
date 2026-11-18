package com.group2.incident.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.event.dto.IncidentChangedNotification;
import com.group2.incident.event.service.EventStreamService;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiveUpdatesTest {

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private EventStreamService eventStreamService;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private MockMvc mvc;

	@MockitoSpyBean
	private AuditService auditService;

	private SignedUp orgA;
	private SignedUp orgB;

	@AfterEach
	void cleanUp() {
		if (orgA != null) {
			orgs.delete(orgA.orgId());
		}
		if (orgB != null) {
			orgs.delete(orgB.orgId());
		}
	}

	@Test
	@DisplayName("Committed change reaches a subscriber of the same org and never one of another org (D46, NFR-14)")
	void committedChangeReachesSubscriberOfSameOrgAndNeverAnotherOrg() throws Exception {
		orgA = orgs.signUp("live-a@test.local", "Org A Live");
		orgB = orgs.signUp("live-b@test.local", "Org B Live");

		SseEmitter emitterA = mock(SseEmitter.class);
		SseEmitter emitterB = mock(SseEmitter.class);

		eventStreamService.subscribe(orgA.orgId(), emitterA);
		eventStreamService.subscribe(orgB.orgId(), emitterB);

		// Initial connection comments sent on subscribe
		verify(emitterA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
		verify(emitterB, times(1)).send(any(SseEmitter.SseEventBuilder.class));

		long serviceA = orgs.createService(orgA.adminToken(), "PaymentService", orgA.adminId());
		String incidentJson = orgs.declareIncident(orgA.adminToken(), serviceA, "Payment Gateway Failure");
		long incidentIdA = TestOrgs.id(incidentJson);

		// Org A subscriber received the incident-changed event
		ArgumentCaptor<SseEmitter.SseEventBuilder> captorA = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
		verify(emitterA, times(2)).send(captorA.capture());

		Set<ResponseBodyEmitter.DataWithMediaType> sentData = captorA.getAllValues().get(1).build();
		boolean containsIncident = sentData.stream()
				.anyMatch(d -> d.getData() instanceof IncidentChangedNotification n && n.id().equals(incidentIdA));
		assertThat(containsIncident).isTrue();

		// Org B subscriber never received any event after the initial comment
		verify(emitterB, times(1)).send(any(SseEmitter.SseEventBuilder.class));
	}

	@Test
	@DisplayName("Rolled-back transaction sends nothing to SSE subscribers (D46)")
	void rolledBackChangeSendsNothing() throws Exception {
		orgA = orgs.signUp("live-rollback@test.local", "Org Rollback Live");

		SseEmitter emitter = mock(SseEmitter.class);
		eventStreamService.subscribe(orgA.orgId(), emitter);

		// Initial connection comment
		verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

		long serviceId = orgs.createService(orgA.adminToken(), "FailingService", orgA.adminId());

		AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
		doThrow(new RuntimeException("Audit failure forcing rollback"))
				.when(spy)
				.record(any(), eq(AuditAction.INCIDENT_CREATED), any(), anyLong(), anyString());

		CreateIncidentRequest request = new CreateIncidentRequest("Will Rollback", "Description", serviceId, Severity.SEV1);

		assertThatThrownBy(() -> OrgContext.runAs(orgA.orgId(), () -> incidentService.createIncident(request, orgA.adminId())))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Audit failure forcing rollback");

		// Nothing sent beyond the initial comment
		verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
	}

	@Test
	@DisplayName("GET /api/events requires authentication")
	void unauthenticatedUserCannotSubscribe() throws Exception {
		mvc.perform(get("/api/events"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("GET /api/events allows authenticated subscriber")
	void authenticatedUserCanSubscribe() throws Exception {
		orgA = orgs.signUp("live-auth@test.local", "Org Auth Live");
		mvc.perform(get("/api/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + orgA.adminToken()))
				.andExpect(status().isOk());
	}
}
