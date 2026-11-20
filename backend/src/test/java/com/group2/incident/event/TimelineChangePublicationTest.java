package com.group2.incident.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.event.dto.IncidentChangedNotification;
import com.group2.incident.event.service.EventStreamService;
import com.group2.incident.incident.repository.EscalationTaskRepository;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.service.EscalationService;
import com.group2.incident.investigation.entity.Investigation;
import com.group2.incident.investigation.entity.InvestigationAction;
import com.group2.incident.investigation.repository.InvestigationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TimelineChangePublicationTest {

	@Autowired private TestOrgs orgs;
	@Autowired private MockMvc mvc;
	@Autowired private EventStreamService streams;
	@Autowired private EscalationService escalation;
	@Autowired private EscalationTaskRepository tasks;
	@Autowired private ServiceRepository services;
	@Autowired private IncidentRepository incidents;
	@Autowired private InvestigationRepository investigations;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private Clock clock;

	private TestOrgs.SignedUp org;
	private TestOrgs.SignedUp otherOrg;
	private final List<Runnable> completions = new ArrayList<>();

	@AfterEach
	void cleanup() {
		completions.forEach(Runnable::run);
		if (org != null) orgs.delete(org.orgId());
		if (otherOrg != null) orgs.delete(otherOrg.orgId());
	}

	@ParameterizedTest
	@ValueSource(strings = {"escalation", "follow-up", "public-update", "action-decision"})
	void otherTimelineWritersReachOnlySameOrgSubscribers(String writer) throws Exception {
		String suffix = UUID.randomUUID().toString();
		org = orgs.signUp("timeline-" + suffix + "@test.local", "Timeline " + suffix);
		otherOrg = orgs.signUp("other-" + suffix + "@test.local", "Other " + suffix);
		long serviceId = orgs.createService(org.adminToken(), "Live service", org.adminId());
		mvc.perform(put("/api/services/{id}/escalation-policy", serviceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"steps\":[{\"stepNumber\":1,\"responderId\":" + org.adminId() + ",\"waitMinutes\":1}]}"))
				.andExpect(status().isOk());
		long incidentId = TestOrgs.id(orgs.declareIncident(org.adminToken(), serviceId, "Live timeline test"));
		SseEmitter sameOrg = subscribe(org);
		SseEmitter differentOrg = subscribe(otherOrg);

		switch (writer) {
			case "escalation" -> OrgContext.runAs(org.orgId(), () -> {
				var task = transactionTemplate.execute(s -> tasks.findByIncidentId(incidentId)
						.map(t -> Map.entry(t.getId(), t.getNextDueAt())).orElseThrow());
				assertThat(escalation.escalateTaskInNewTransaction(task.getKey(), task.getValue())).isTrue();
				return null;
			});
			case "follow-up" -> mvc.perform(post("/api/incidents/{id}/follow-ups", incidentId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"title\":\"Investigate pool\",\"kind\":\"PREVENTIVE\",\"ownerId\":"
							+ org.adminId() + ",\"dueDate\":\"2099-12-01\"}"))
					.andExpect(status().isCreated());
			case "public-update" -> {
				OrgContext.runAs(org.orgId(), () -> transactionTemplate.execute(s -> {
					services.findById(serviceId).orElseThrow().publishAs(true, "Public service", clock.instant());
					return null;
				}));
				mvc.perform(post("/api/incidents/{id}/public-updates", incidentId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INVESTIGATING\",\"message\":\"Investigating the outage\"}"))
						.andExpect(status().isCreated());
			}
			case "action-decision" -> {
				var ids = OrgContext.runAs(org.orgId(), () -> transactionTemplate.execute(s -> {
					var incident = incidents.findById(incidentId).orElseThrow();
					var investigation = new Investigation(incident, "Test", "completed", 1, 1, 1, clock.instant());
					var action = new InvestigationAction(investigation, "Inspect pool");
					investigation.addAction(action);
					investigations.saveAndFlush(investigation);
					return Map.entry(investigation.getId(), action.getId());
				}));
				mvc.perform(post("/api/investigations/{id}/actions/{actionId}/decision", ids.getKey(), ids.getValue())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + org.adminToken())
						.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPTED\"}"))
						.andExpect(status().isOk());
			}
			default -> throw new IllegalArgumentException(writer);
		}

		assertThat(notifications(sameOrg)).containsExactly(incidentId);
		assertThat(notifications(differentOrg)).isEmpty();
	}

	private SseEmitter subscribe(TestOrgs.SignedUp subscriber) {
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(call -> { completions.add(call.getArgument(0)); return null; }).when(emitter).onCompletion(any());
		streams.subscribe(subscriber.orgId(), subscriber.adminId(), emitter);
		return emitter;
	}

	private static List<Long> notifications(SseEmitter emitter) throws Exception {
		var events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
		verify(emitter, atLeastOnce()).send(events.capture());
		return events.getAllValues().stream().flatMap(e -> e.build().stream())
				.map(d -> d.getData()).filter(IncidentChangedNotification.class::isInstance)
				.map(IncidentChangedNotification.class::cast).map(IncidentChangedNotification::id).toList();
	}
}
