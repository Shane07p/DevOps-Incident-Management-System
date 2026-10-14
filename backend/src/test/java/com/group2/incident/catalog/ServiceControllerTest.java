package com.group2.incident.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.IntegrationTest;
import com.group2.incident.audit.AuditEntry;
import com.group2.incident.audit.AuditEntryRepository;
import com.group2.incident.auth.AppUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** US-05 against a real database. The dev seed provides users admin/engineer/viewer and "Payments". */
@IntegrationTest
@WithMockUser(username = "admin", roles = "ADMIN")
class ServiceControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AuditEntryRepository auditEntries;

	@Autowired
	private JdbcTemplate jdbc;

	@PersistenceContext
	private EntityManager entityManager;

	private long adminId;

	private long engineerId;

	@BeforeEach
	void setUp() {
		adminId = users.findByUsername("admin").orElseThrow().getId();
		engineerId = users.findByUsername("engineer").orElseThrow().getId();
	}

	@Test
	void adminRegistersAServiceWithTargetsPerSeverity() throws Exception {
		create(body("Search", adminId, "HIGH", targets(10, 120)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/services/")))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Search"))
				.andExpect(jsonPath("$.description").value("Product search"))
				.andExpect(jsonPath("$.owner.id").value(adminId))
				.andExpect(jsonPath("$.owner.displayName").value("Dev Admin"))
				.andExpect(jsonPath("$.criticality").value("HIGH"))
				.andExpect(jsonPath("$.targets.SEV1.responseMinutes").value(10))
				.andExpect(jsonPath("$.targets.SEV4.resolutionMinutes").value(120))
				.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void savedServiceCanBeReadBack() throws Exception {
		long id = createdId(body("Search", adminId, "HIGH", targets(10, 120)));

		mvc.perform(get("/api/services/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Search"))
				.andExpect(jsonPath("$.targets.SEV2.responseMinutes").value(10));
		mvc.perform(get("/api/services"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", contains("Payments", "Search")));
	}

	@Test
	void omittedResponseTargetsTakeTheDefaults() throws Exception {
		create(body("Search", adminId, "LOW", targets(null, 2000)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.targets.SEV1.responseMinutes").value(15))
				.andExpect(jsonPath("$.targets.SEV2.responseMinutes").value(60))
				.andExpect(jsonPath("$.targets.SEV3.responseMinutes").value(240))
				.andExpect(jsonPath("$.targets.SEV4.responseMinutes").value(1440));
	}

	@Test
	void defaultTargetsEndpointGivesTheSurveyDefaults() throws Exception {
		mvc.perform(get("/api/services/default-targets"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.SEV1.responseMinutes").value(15))
				.andExpect(jsonPath("$.SEV4.responseMinutes").value(1440))
				.andExpect(jsonPath("$.SEV4.resolutionMinutes").doesNotExist());
	}

	@Test
	void missingFieldsAreNamedAndNothingIsSaved() throws Exception {
		create("{\"targets\": " + targets(10, 120) + "}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.name").value("Name is required"))
				.andExpect(jsonPath("$.errors.ownerId").value("Owner is required"))
				.andExpect(jsonPath("$.errors.criticality").value("Criticality is required"));

		assertOnlyPaymentsExists();
	}

	@Test
	void blankNameIsRejected() throws Exception {
		create(body("   ", adminId, "LOW", targets(10, 120)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").value("Name is required"));
	}

	@Test
	void serviceNeedsTargetsForEverySeverity() throws Exception {
		String threeSeverities = "{\"SEV1\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
				+ " \"SEV2\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
				+ " \"SEV3\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60}}";
		create(body("Search", adminId, "LOW", threeSeverities))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['targets.SEV4']").value("Targets for SEV4 are required"));

		assertOnlyPaymentsExists();
	}

	@Test
	void resolutionShorterThanResponseIsRejected() throws Exception {
		create(body("Search", adminId, "LOW", targets(60, 30)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['targets.SEV1.resolutionMinutes']")
						.value("Resolution target can't be shorter than the response target"));
	}

	@Test
	void unknownOwnerIsRejected() throws Exception {
		create(body("Search", 999_999, "LOW", targets(10, 120)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.ownerId").value("Owner not found"));
	}

	@Test
	void unknownCriticalityOrSeverityIsAMalformedBody() throws Exception {
		create(body("Search", adminId, "SEVERE", targets(10, 120)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Malformed request body"));
		create(body("Search", adminId, "LOW", "{\"SEV9\": {\"responseMinutes\": 1, \"resolutionMinutes\": 1}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Malformed request body"));
	}

	@Test
	void serviceNamesAreUniqueIgnoringCase() throws Exception {
		create(body("  payments ", adminId, "LOW", targets(10, 120)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("A service named payments already exists"));
	}

	@Test
	void adminEditsOwnerCriticalityAndTargets() throws Exception {
		long id = createdId(body("Search", adminId, "HIGH", targets(10, 120)));

		mvc.perform(put("/api/services/" + id).contentType(MediaType.APPLICATION_JSON)
						.content(body("Search v2", engineerId, "CRITICAL", targets(5, 120))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value("Search v2"))
				.andExpect(jsonPath("$.owner.displayName").value("Dev Engineer"))
				.andExpect(jsonPath("$.criticality").value("CRITICAL"))
				.andExpect(jsonPath("$.targets.SEV3.responseMinutes").value(5));

		mvc.perform(get("/api/services/" + id))
				.andExpect(jsonPath("$.targets.SEV3.responseMinutes").value(5))
				.andExpect(jsonPath("$.targets.SEV3.resolutionMinutes").value(120));
	}

	@Test
	void renamingOntoAnotherServiceIsAConflictButKeepingTheNameIsNot() throws Exception {
		long id = createdId(body("Search", adminId, "HIGH", targets(10, 120)));

		update(id, body("PAYMENTS", adminId, "HIGH", targets(10, 120))).andExpect(status().isConflict());
		update(id, body("search", adminId, "HIGH", targets(10, 120))).andExpect(status().isOk());
	}

	@Test
	void editingAnUnknownServiceIs404() throws Exception {
		update(999_999, body("Ghost", adminId, "LOW", targets(10, 120)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Service 999999 not found"));
		mvc.perform(get("/api/services/999999")).andExpect(status().isNotFound());
	}

	@Test
	void disabledUserCantBecomeOwnerButAnExistingOwnerCanStay() throws Exception {
		long id = createdId(body("Search", engineerId, "HIGH", targets(10, 120)));
		jdbc.update("update app_user set enabled = false where id = ?", engineerId);
		// Real requests each get a fresh persistence context; this test shares one, so drop the cached user.
		entityManager.clear();

		create(body("Other", engineerId, "LOW", targets(10, 120)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.ownerId").value("Owner not found"));
		update(id, body("Search", engineerId, "CRITICAL", targets(10, 120))).andExpect(status().isOk());
	}

	@Test
	void createAndEditAreAudited() throws Exception {
		long id = createdId(body("Search", adminId, "HIGH", targets(10, 120)));
		update(id, body("Search", adminId, "CRITICAL", targets(10, 120))).andExpect(status().isOk());

		List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByIdAsc("SERVICE", id);
		assertThat(entries).extracting(AuditEntry::getAction).containsExactly("SERVICE_CREATED", "SERVICE_UPDATED");
		assertThat(entries).allSatisfy(entry -> {
			assertThat(entry.getActorId()).isEqualTo(adminId);
			assertThat(entry.getRecordedAt()).isNotNull();
		});
		assertThat(entries.get(0).getDetails()).contains("Search (HIGH)", "SEV1 10/120 min");
		assertThat(entries.get(1).getDetails()).isEqualTo("criticality: HIGH → CRITICAL");
	}

	@Test
	void savingWithoutChangesWritesNoAuditEntry() throws Exception {
		long id = createdId(body("Search", adminId, "HIGH", targets(10, 120)));
		update(id, body("Search", adminId, "HIGH", targets(10, 120))).andExpect(status().isOk());

		assertThat(auditEntries.findByItemTypeAndItemIdOrderByIdAsc("SERVICE", id)).hasSize(1);
	}

	@Test
	@WithMockUser(username = "engineer", roles = "ENGINEER")
	void engineerCanReadButNotChangeServices() throws Exception {
		mvc.perform(get("/api/services")).andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", hasItem("Payments")));
		create(body("Search", adminId, "LOW", targets(10, 120))).andExpect(status().isForbidden());
		update(1, body("Search", adminId, "LOW", targets(10, 120))).andExpect(status().isForbidden());
		assertOnlyPaymentsExists();
	}

	@Test
	@WithMockUser(username = "viewer", roles = "VIEWER")
	void viewerCanReadButNotChangeServices() throws Exception {
		mvc.perform(get("/api/services")).andExpect(status().isOk());
		create(body("Search", adminId, "LOW", targets(10, 120))).andExpect(status().isForbidden());
		update(1, body("Search", adminId, "LOW", targets(10, 120))).andExpect(status().isForbidden());
	}

	private ResultActions create(String json) throws Exception {
		return mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions update(long id, String json) throws Exception {
		return mvc.perform(put("/api/services/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long createdId(String json) throws Exception {
		String location = create(json).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private void assertOnlyPaymentsExists() throws Exception {
		mvc.perform(get("/api/services"))
				.andExpect(jsonPath("$[*].name", contains("Payments")));
	}

	private static String body(String name, long ownerId, String criticality, String targets) {
		return """
				{"name": "%s", "description": "Product search", "ownerId": %d, "criticality": "%s", "targets": %s}
				""".formatted(name, ownerId, criticality, targets);
	}

	private static String targets(Integer response, Integer resolution) {
		String one = "{\"responseMinutes\": %s, \"resolutionMinutes\": %s}".formatted(response, resolution);
		return "{\"SEV1\": %s, \"SEV2\": %s, \"SEV3\": %s, \"SEV4\": %s}".formatted(one, one, one, one);
	}

}
