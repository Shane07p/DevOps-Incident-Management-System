package com.group2.incident.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import java.util.ArrayList;
import java.util.List;
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
	private final List<Runnable> completions = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		completions.forEach(Runnable::run);
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

		subscribe(orgA, emitterA);
		subscribe(orgB, emitterB);

		long serviceA = orgs.createService(orgA.adminToken(), "PaymentService", orgA.adminId());
		String incidentJson = orgs.declareIncident(orgA.adminToken(), serviceA, "Payment Gateway Failure");
		long incidentIdA = TestOrgs.id(incidentJson);

		assertThat(notifications(emitterA)).containsExactly(incidentIdA);
		assertThat(notifications(emitterB)).isEmpty();
	}

	@Test
	@DisplayName("Rolled-back transaction sends nothing to SSE subscribers (D46)")
	void rolledBackChangeSendsNothing() throws Exception {
		orgA = orgs.signUp("live-rollback@test.local", "Org Rollback Live");

		SseEmitter emitter = mock(SseEmitter.class);
		subscribe(orgA, emitter);

		long serviceId = orgs.createService(orgA.adminToken(), "FailingService", orgA.adminId());

		AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
		doThrow(new RuntimeException("Audit failure forcing rollback"))
				.when(spy)
				.record(any(), eq(AuditAction.INCIDENT_CREATED), any(), anyLong(), anyString());

		CreateIncidentRequest request = new CreateIncidentRequest("Will Rollback", "Description", serviceId, Severity.SEV1);

		assertThatThrownBy(() -> OrgContext.runAs(orgA.orgId(), () -> incidentService.createIncident(request, orgA.adminId())))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Audit failure forcing rollback");

		assertThat(notifications(emitter)).isEmpty();
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
		var result = mvc.perform(get("/api/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + orgA.adminToken()))
				.andExpect(status().isOk()).andReturn();
		result.getRequest().getAsyncContext().complete();
	}

	private void subscribe(SignedUp org, SseEmitter emitter) {
		eventStreamService.subscribe(org.orgId(), org.adminId(), emitter);
		var callback = ArgumentCaptor.forClass(Runnable.class);
		verify(emitter).onCompletion(callback.capture());
		completions.add(callback.getValue());
	}

	private static List<Long> notifications(SseEmitter emitter) throws Exception {
		var events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
		verify(emitter, atLeastOnce()).send(events.capture());
		return events.getAllValues().stream().flatMap(e -> e.build().stream())
				.map(d -> d.getData()).filter(IncidentChangedNotification.class::isInstance)
				.map(IncidentChangedNotification.class::cast).map(IncidentChangedNotification::id).toList();
	}
}
