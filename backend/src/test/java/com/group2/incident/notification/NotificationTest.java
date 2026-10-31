package com.group2.incident.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class NotificationTest {

	private static final String PASSWORD = "DevPassw0rd!";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository userRepository;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private AuditService auditService;

	private AppUser responder;
	private AppUser otherEngineer;
	private ServiceEntity testService;

	@BeforeEach
	void setUp() {
		Instant now = clock.instant();
		responder = userRepository.saveAndFlush(
				new AppUser("responder", "OnCall Responder", passwordEncoder.encode(PASSWORD), Role.ENGINEER, now)
		);
		otherEngineer = userRepository.saveAndFlush(
				new AppUser("other_eng", "Other Engineer", passwordEncoder.encode(PASSWORD), Role.ENGINEER, now)
		);

		// No shifts, so every new incident goes to the fallback contact, the responder.
		ServiceEntity service = new ServiceEntity("NotificationService_Test", "Test Service", responder, Criticality.HIGH, now);
		service.changeFallbackContact(responder);
		for (Severity severity : Severity.values()) {
			service.putTarget(severity, 30, 120);
		}
		testService = serviceRepository.saveAndFlush(service);
	}

	// TC-FR-07.8a runs outside the test transaction, so the setup above is committed for it: delete it again.
	@AfterEach
	void deleteCommittedRows() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		serviceRepository.delete(testService);
		userRepository.deleteAll(List.of(responder, otherEngineer));
	}

	@Test
	@DisplayName("TC-FR-07.1a: An incident is assigned -> The assignee has one unread notification")
	void tcFr07_1a_assignedIncidentCreatesUnreadNotificationForAssignee() throws Exception {
		CreateIncidentRequest request = new CreateIncidentRequest(
				"High CPU Usage",
				"CPU utilization exceeded 95%",
				testService.getId(),
				Severity.SEV2
		);
		var incidentResponse = incidentService.createIncident(request, responder.getId());

		String responderToken = tokenFor("responder");

		mvc.perform(get("/api/notifications")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + responderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unreadCount").value(1))
				.andExpect(jsonPath("$.notifications.length()").value(1))
				.andExpect(jsonPath("$.notifications[0].incidentId").value(incidentResponse.id()))
				.andExpect(jsonPath("$.notifications[0].message").value(org.hamcrest.Matchers.containsString("High CPU Usage")))
				.andExpect(jsonPath("$.notifications[0].readAt").isEmpty());
	}

	@Test
	@DisplayName("TC-FR-07.1b: Another user requests that notification -> 404; they never see it")
	void tcFr07_1b_userIsolationCannotAccessOtherUsersNotification() throws Exception {
		// Create notification directly for responder
		Notification notif = notificationRepository.saveAndFlush(
				new Notification(responder, null, "Private alert", clock.instant())
		);

		String otherToken = tokenFor("other_eng");

		// Other user attempts to mark responder's notification as read -> 404
		mvc.perform(post("/api/notifications/" + notif.getId() + "/read")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
				.andExpect(status().isNotFound());

		// Other user's notification inbox is empty
		mvc.perform(get("/api/notifications")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unreadCount").value(0))
				.andExpect(jsonPath("$.notifications.length()").value(0));
	}

	@Test
	@DisplayName("TC-FR-07.1c: Mark it as read -> The unread count goes down by one")
	void tcFr07_1c_markAsReadDecrementsUnreadCount() throws Exception {
		Notification notif1 = notificationRepository.saveAndFlush(
				new Notification(responder, null, "Alert 1", clock.instant())
		);
		notificationRepository.saveAndFlush(
				new Notification(responder, null, "Alert 2", clock.instant())
		);

		String responderToken = tokenFor("responder");

		// Initially 2 unread
		mvc.perform(get("/api/notifications")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + responderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unreadCount").value(2))
				.andExpect(jsonPath("$.notifications.length()").value(2));

		// Mark notif1 as read
		mvc.perform(post("/api/notifications/" + notif1.getId() + "/read")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + responderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(notif1.getId()))
				.andExpect(jsonPath("$.readAt").isNotEmpty());

		// Now 1 unread
		mvc.perform(get("/api/notifications")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + responderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unreadCount").value(1))
				.andExpect(jsonPath("$.notifications.length()").value(2));
	}

	@Test
	@DisplayName("TC-FR-07.8a: The change that caused the notification rolls back -> No notification is left")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void tcFr07_8a_transactionRollbackCleansUpNotification() {
		long notificationsBefore = notificationRepository.count();
		long incidentsBefore = incidentRepository.count();

		// The audit entry is written after the assignee's notification, so failing it rolls that back.
		AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
		doThrow(new RuntimeException("Simulated audit failure"))
				.when(spy)
				.record(any(), eq(AuditAction.INCIDENT_CREATED), any(), anyLong(), anyString());

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Rollback Test Incident",
				"Should not persist",
				testService.getId(),
				Severity.SEV1
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, responder.getId()))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Simulated audit failure");

		assertThat(notificationRepository.count()).isEqualTo(notificationsBefore);
		assertThat(incidentRepository.count()).isEqualTo(incidentsBefore);
	}

	@Test
	@DisplayName("Unauthorized access: unauthenticated requests to notifications return 401")
	void unauthenticatedRequestsReturn401() throws Exception {
		mvc.perform(get("/api/notifications"))
				.andExpect(status().isUnauthorized());

		mvc.perform(post("/api/notifications/999/read"))
				.andExpect(status().isUnauthorized());
	}

	private String tokenFor(String username) throws Exception {
		String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
		String response = mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.token");
	}
}
