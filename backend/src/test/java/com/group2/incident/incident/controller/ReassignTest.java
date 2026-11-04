package com.group2.incident.incident.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.notification.entity.Notification;
import com.group2.incident.notification.repository.NotificationRepository;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ReassignTest {

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ServiceRepository services;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private TimelineEntryRepository timelineEntries;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private MembershipRepository memberships;

	@Autowired
	private OrganizationRepository orgs;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private Clock clock;

	private AppUser admin;
	private String adminToken;

	private AppUser engineer1;
	private String engineer1Token;

	private AppUser engineer2;
	private String engineer2Token;

	private AppUser viewer;
	private String viewerToken;

	private ServiceEntity paymentsService;

	private long incidentSequence = 800L;

	@BeforeEach
	void setUp() throws Exception {
		admin = accounts.create("reassign-admin@test.local", Role.ADMIN);
		adminToken = accounts.token("reassign-admin@test.local");

		engineer1 = accounts.create("reassign-eng1@test.local", Role.ENGINEER);
		engineer1Token = accounts.token("reassign-eng1@test.local");

		engineer2 = accounts.create("reassign-eng2@test.local", Role.ENGINEER);
		engineer2Token = accounts.token("reassign-eng2@test.local");

		viewer = accounts.create("reassign-viewer@test.local", Role.VIEWER);
		viewerToken = accounts.token("reassign-viewer@test.local");

		Instant now = clock.instant();
		ServiceEntity service = new ServiceEntity("Payments", "Payments service", admin, Criticality.HIGH, now);
		service.putTarget(Severity.SEV1, 15, 240);
		service.putTarget(Severity.SEV2, 60, 480);
		service.putTarget(Severity.SEV3, 240, 2880);
		service.putTarget(Severity.SEV4, 1440, 10080);
		paymentsService = services.saveAndFlush(service);
	}

	@Test
	@DisplayName("TC-FR-05.3a: Successful reassignment updates owner, records timeline, creates notification, leaves SLA targets unchanged")
	void successfulReassignment() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);
		Long initialVersion = incident.getVersion();
		Instant responseDueAtBefore = incident.getResponseDueAt();
		Instant resolutionDueAtBefore = incident.getResolutionDueAt();

		long notificationsBefore = notifications.count();

		String body = "{\"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incident.getId()))
				.andExpect(jsonPath("$.assignee.id").value(engineer2.getId()))
				.andExpect(jsonPath("$.assignee.displayName").value(engineer2.getDisplayName()))
				.andExpect(jsonPath("$.responseTargetMinutes").value(60))
				.andExpect(jsonPath("$.resolutionTargetMinutes").value(480))
				.andExpect(jsonPath("$.responseDueAt").value(responseDueAtBefore.toString()))
				.andExpect(jsonPath("$.resolutionDueAt").value(resolutionDueAtBefore.toString()));

		// Database verification
		Incident updated = incidents.findById(incident.getId()).orElseThrow();
		assertThat(updated.getAssignee().getId()).isEqualTo(engineer2.getId());
		assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);

		// Timeline verification
		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry lastEntry = entries.get(entries.size() - 1);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.REASSIGNED);
		assertThat(lastEntry.getActor().getId()).isEqualTo(engineer1.getId());
		assertThat(lastEntry.getDetails()).isEqualTo("Reassigned from " + engineer1.getDisplayName() + " to " + engineer2.getDisplayName());

		// Notification verification
		assertThat(notifications.count()).isEqualTo(notificationsBefore + 1);
		List<Notification> userNotifications = notifications.findAllByRecipientIdOrderByCreatedAtDescIdDesc(engineer2.getId());
		assertThat(userNotifications).isNotEmpty();
		Notification notification = userNotifications.get(0);
		assertThat(notification.getIncident().getId()).isEqualTo(incident.getId());
		assertThat(notification.getMessage()).contains(incident.getReference());
		assertThat(notification.getReadAt()).isNull();
	}

	@Test
	@DisplayName("Reassignment from unassigned incident records 'unassigned' in timeline details")
	void reassignFromUnassignedIncident() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, null);

		String body = "{\"assigneeId\": %d}".formatted(engineer1.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.id").value(engineer1.getId()));

		List<TimelineEntry> entries = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		TimelineEntry lastEntry = entries.get(entries.size() - 1);
		assertThat(lastEntry.getEventType()).isEqualTo(TimelineEventType.REASSIGNED);
		assertThat(lastEntry.getDetails()).isEqualTo("Reassigned from unassigned to " + engineer1.getDisplayName());
	}

	@Test
	@DisplayName("Reassign with invalid/unknown user ID returns 400 Bad Request naming assigneeId")
	void reassignWithInvalidUserIdReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);

		String body = "{\"assigneeId\": 999999}";

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.assigneeId").value("Assignee user not found"));
	}

	@Test
	@DisplayName("Reassign with disabled user returns 400 Bad Request naming assigneeId")
	void reassignWithDisabledUserReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);
		AppUser disabledUser = accounts.create("disabled-user@test.local", Role.ENGINEER);
		accounts.membership(disabledUser).disable();
		entityManager.flush();

		String body = "{\"assigneeId\": %d}".formatted(disabledUser.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.assigneeId").value("Assignee must be an enabled user"));
	}

	@Test
	@DisplayName("Reassign with user from another org returns 400 Bad Request")
	void reassignWithCrossOrgUserReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);

		Instant now = clock.instant();
		Organization otherOrg = orgs.saveAndFlush(new Organization("Other Org", "other-org-reassign", now));
		AppUser otherOrgUser = users.saveAndFlush(new AppUser("crossorg@other.local", "Cross Org User", "hash", now));
		memberships.saveAndFlush(new Membership(otherOrg, otherOrgUser, Role.ENGINEER, now));

		String body = "{\"assigneeId\": %d}".formatted(otherOrgUser.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.assigneeId").value("Assignee user not found"));
	}

	@Test
	@DisplayName("Reassign with null assigneeId returns 400 Bad Request")
	void reassignWithNullAssigneeIdReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"assigneeId\": null}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.assigneeId").value("Assignee is required"));
	}

	@Test
	@DisplayName("Reassign nonexistent incident returns 404 Not Found")
	void reassignNonexistentIncidentReturns404() throws Exception {
		String body = "{\"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/999999/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("Reassign CLOSED incident returns 409 Conflict")
	void reassignClosedIncidentReturns409() throws Exception {
		Incident incident = createIncident(IncidentStatus.CLOSED, engineer1);

		String body = "{\"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Cannot reassign a closed incident"));
	}

	@Test
	@DisplayName("VIEWER cannot reassign incidents (403 Forbidden)")
	void viewerForbidden() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);

		String body = "{\"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("ADMIN and ENGINEER can reassign incidents (200 OK)")
	void adminAndEngineerPermitted() throws Exception {
		Incident incidentAdmin = createIncident(IncidentStatus.OPEN, engineer1);
		Incident incidentEng = createIncident(IncidentStatus.OPEN, engineer1);

		String body = "{\"assigneeId\": %d}".formatted(engineer2.getId());

		mvc.perform(put("/api/incidents/" + incidentAdmin.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.id").value(engineer2.getId()));

		mvc.perform(put("/api/incidents/" + incidentEng.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer2Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.id").value(engineer2.getId()));
	}

	@Test
	@DisplayName("Reassign to same assignee returns 400 Bad Request and does not create timeline event or notification")
	void reassignToSameAssigneeReturns400() throws Exception {
		Incident incident = createIncident(IncidentStatus.OPEN, engineer1);
		int initialTimelineCount = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId()).size();
		int initialNotificationCount = notifications.findAll().size();

		String body = "{\"assigneeId\": %d}".formatted(engineer1.getId());

		mvc.perform(put("/api/incidents/" + incident.getId() + "/assignee")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + engineer1Token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.assigneeId").value("Already assigned to this person"));

		List<TimelineEntry> timeline = timelineEntries.findAllByIncidentIdOrderByRecordedAtAscIdAsc(incident.getId());
		assertThat(timeline).hasSize(initialTimelineCount);
		assertThat(timeline).noneMatch(entry -> entry.getEventType() == TimelineEventType.REASSIGNED);

		assertThat(notifications.findAll()).hasSize(initialNotificationCount);
	}

	private Incident createIncident(IncidentStatus status, AppUser assignee) {
		Instant now = clock.instant();
		Incident incident = new Incident(
				"Test Incident",
				"Description",
				paymentsService,
				Severity.SEV2,
				status,
				engineer1,
				now,
				60,
				480
		);
		if (assignee != null) {
			incident.assignTo(assignee);
		}
		incident.assignNumber(incidentSequence++);
		return incidents.saveAndFlush(incident);
	}
}

