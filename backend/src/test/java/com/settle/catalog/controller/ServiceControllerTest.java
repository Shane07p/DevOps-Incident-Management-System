package com.settle.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.settle.audit.entity.AuditAction;
import com.settle.audit.entity.AuditEntry;
import com.settle.audit.entity.AuditItemType;
import com.settle.audit.repository.AuditEntryRepository;
import com.settle.auth.entity.AppUser;
import com.settle.auth.entity.Role;
import com.settle.auth.repository.AppUserRepository;
import com.settle.catalog.repository.ServiceRepository;
import com.settle.support.IntegrationTest;
import com.settle.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
public class ServiceControllerTest {

    private static final String PASSWORD = "DevPassw0rd!";

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ServiceRepository services;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private AppUser admin;
    private AppUser engineer;
    private AppUser viewer;
    private String adminToken;
    private String engineerToken;
    private String viewerToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createUser("admin", Role.ADMIN);
        engineer = createUser("engineer", Role.ENGINEER);
        viewer = createUser("viewer", Role.VIEWER);

        adminToken = tokenFor("admin");
        engineerToken = tokenFor("engineer");
        viewerToken = tokenFor("viewer");
    }

    @Test
    void listReturnsAllServicesInAlphabeticalOrder() throws Exception {
        createService("Payments", admin.getId(), "CRITICAL", targets(15, 240));
        createService("Auth", admin.getId(), "HIGH", targets(15, 240));

        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Auth"))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[1].version").value(0))
                .andExpect(jsonPath("$[1].name").value("Payments"));
    }

    @Test
    void getOneReturnsServiceDetails() throws Exception {
        long id = createService("Payments", admin.getId(), "CRITICAL", targets(15, 240));

        mvc.perform(get("/api/services/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Payments"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.owner.id").value(admin.getId()))
                .andExpect(jsonPath("$.owner.displayName").value("Test admin"))
                .andExpect(jsonPath("$.targets.SEV1.responseMinutes").value(15))
                .andExpect(jsonPath("$.targets.SEV1.resolutionMinutes").value(240));
    }

    @Test
    void getUnknownServiceReturns404() throws Exception {
        mvc.perform(get("/api/services/999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Service not found"));
    }

    @Test
    void adminCreatesServiceWithAudit() throws Exception {
        String body = body("Checkout", admin.getId(), "HIGH", targets(10, 120));

        String location = mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Checkout"))
                .andExpect(jsonPath("$.criticality").value("HIGH"))
                .andReturn().getResponse().getHeader("Location");

        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        List<AuditEntry> auditList = auditEntries.findAll().stream()
                .filter(e -> e.getItemType() == AuditItemType.SERVICE && e.getItemId() == id)
                .toList();
        assertThat(auditList).hasSize(1);
        assertThat(auditList.get(0).getAction()).isEqualTo(AuditAction.SERVICE_CREATED);
        assertThat(auditList.get(0).getActor().getId()).isEqualTo(admin.getId());
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        String invalidBody = "{\"description\": \"missing name, owner, criticality, targets\"}";

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Name is required"))
                .andExpect(jsonPath("$.errors.ownerId").value("Owner is required"))
                .andExpect(jsonPath("$.errors.criticality").value("Criticality is required"))
                .andExpect(jsonPath("$.errors.targets").value("Targets are required"));
    }

    @Test
    void missingSeverityTargetIsRejected() throws Exception {
        String threeSeverities = "{\"SEV1\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
                + " \"SEV2\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60},"
                + " \"SEV3\": {\"responseMinutes\": 10, \"resolutionMinutes\": 60}}";
        String body = body("Catalog", admin.getId(), "LOW", threeSeverities);

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['targets.SEV4']").value("Targets for SEV4 are required"));
    }

    @Test
    void resolutionShorterThanResponseIsRejected() throws Exception {
        String invalidTargets = "{\"SEV1\": {\"responseMinutes\": 60, \"resolutionMinutes\": 30},"
                + " \"SEV2\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60},"
                + " \"SEV3\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60},"
                + " \"SEV4\": {\"responseMinutes\": 60, \"resolutionMinutes\": 60}}";
        String body = body("Catalog", admin.getId(), "LOW", invalidTargets);

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['targets.SEV1.resolutionMinutes']")
                        .value("Resolution target can't be shorter than the response target"));
    }

    @Test
    void duplicateServiceNameIsRejected() throws Exception {
        createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("billing", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isConflict());
    }

    @Test
    void adminUpdatesService() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", engineer.getId(), "CRITICAL", targets(5, 120))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Billing v2"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.owner.displayName").value("Test engineer"));

        List<AuditEntry> auditList = auditEntries.findAll().stream()
                .filter(e -> e.getItemType() == AuditItemType.SERVICE && e.getItemId() == id)
                .toList();
        assertThat(auditList).hasSize(2);
        AuditEntry updated = auditList.stream()
                .filter(e -> e.getAction() == AuditAction.SERVICE_UPDATED)
                .findFirst()
                .orElseThrow();
        assertThat(updated.getActor().getId()).isEqualTo(admin.getId());
        assertThat(updated.getDetails())
                .contains("name: Billing → Billing v2")
                .contains("owner: ")
                .contains("criticality: MEDIUM → CRITICAL");
    }

    @Test
    void adminPutsServiceOnTheStatusPageUnderAPublicName() throws Exception {
        long id = createService("billing-api-prod", admin.getId(), "MEDIUM", targets(15, 240));
        mvc.perform(get("/api/services/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicVisible").value(false))
                .andExpect(jsonPath("$.publicName").value(nullValue()));

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPublication(body("billing-api-prod", admin.getId(), "MEDIUM", targets(15, 240)),
                                "true", "\" Billing \"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicVisible").value(true))
                .andExpect(jsonPath("$.publicName").value("Billing"));

        // A form written before #160 sends neither field: the service stays on the page.
        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(body("billing-api", admin.getId(), "MEDIUM", targets(15, 240)), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicVisible").value(true))
                .andExpect(jsonPath("$.publicName").value("Billing"));

        assertThat(auditEntries.findAll().stream()
                .filter(e -> e.getItemType() == AuditItemType.SERVICE && e.getItemId() == id
                        && e.getAction() == AuditAction.SERVICE_UPDATED)
                .map(AuditEntry::getDetails))
                .anySatisfy(details -> assertThat(details)
                        .contains("shown on the status page")
                        .contains("public name: none → Billing"));
    }

    @Test
    void aServiceOnTheStatusPageNeedsAPublicName() throws Exception {
        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPublication(body("Ledger", admin.getId(), "LOW", targets(15, 240)), "true", "\"  \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.publicName").isNotEmpty());

        long id = createService("Ledger", admin.getId(), "LOW", targets(15, 240));
        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPublication(body("Ledger", admin.getId(), "LOW", targets(15, 240)), "true", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.publicName").isNotEmpty());
    }

    @Test
    void engineerCannotCreateOrUpdate() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("New Service", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateOrUpdate() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));

        mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("New Service", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing v2", admin.getId(), "LOW", targets(15, 240))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallReturns401() throws Exception {
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicationPatchPreservesOtherFieldsAndAuditsTheChange() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));
        mvc.perform(patch("/api/services/{id}/status-page", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicVisible\":true,\"publicName\":\" Card payments \",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicVisible").value(true))
                .andExpect(jsonPath("$.publicName").value("Card payments"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Billing"))
                .andExpect(jsonPath("$.description").value("Test service"))
                .andExpect(jsonPath("$.owner.id").value(admin.getId()))
                .andExpect(jsonPath("$.criticality").value("MEDIUM"))
                .andExpect(jsonPath("$.targets.SEV1.responseMinutes").value(15))
                .andExpect(jsonPath("$.targets.SEV4.resolutionMinutes").value(240));
        assertThat(auditEntries.findAll().stream()
                .filter(entry -> entry.getItemId() == id && entry.getItemType() == AuditItemType.SERVICE
                        && entry.getAction() == AuditAction.SERVICE_UPDATED))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
                    assertThat(entry.getDetails()).contains("shown on the status page", "public name: none → Card payments");
                });
        mvc.perform(patch("/api/services/{id}/status-page", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicVisible\":false,\"publicName\":null,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicName").value(nullValue()))
                .andExpect(jsonPath("$.publicVisible").value(false))
                .andExpect(jsonPath("$.version").value(2));
    }

    @ParameterizedTest
    @CsvSource({"PUT,PUT", "PATCH,PATCH", "PUT,PATCH", "PATCH,PUT"})
    void staleVersionsCannotOverwriteEitherUpdatePath(String first, String second) throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));
        String putBody = body("First edit", admin.getId(), "MEDIUM", targets(15, 240));
        String patchBody = "{\"publicVisible\":true,\"publicName\":\"First public name\",\"version\":0}";
        String firstPath = "/api/services/" + id + (first.equals("PATCH") ? "/status-page" : "");
        String secondPath = "/api/services/" + id + (second.equals("PATCH") ? "/status-page" : "");
        mvc.perform(request(HttpMethod.valueOf(first), firstPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(first.equals("PUT") ? putBody : patchBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(request(HttpMethod.valueOf(second), secondPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second.equals("PUT") ? putBody.replace("First edit", "Lost edit") : patchBody.replace("First public name", "Lost edit")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errors").isMap())
                .andExpect(jsonPath("$.message").value("This service was changed by someone else. Reload to see the latest."));
        mvc.perform(get("/api/services/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value(first.equals("PUT") ? "First edit" : "Billing"))
                .andExpect(jsonPath("$.publicName").value(first.equals("PATCH") ? "First public name" : null));
        assertThat(auditEntries.findAll().stream().filter(entry -> entry.getItemId() == id
                && entry.getItemType() == AuditItemType.SERVICE && entry.getAction() == AuditAction.SERVICE_UPDATED)).hasSize(1);
    }

    @Test
    void updateRequiresAVersionAndPublicationValidatesItsInputs() throws Exception {
        long id = createService("Billing", admin.getId(), "MEDIUM", targets(15, 240));
        mvc.perform(put("/api/services/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Billing", admin.getId(), "MEDIUM", targets(15, 240)).replace(", \"version\":0", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.version").value("Version is required"));
        String[][] invalid = {
                {"{\"publicVisible\":true,\"publicName\":null,\"version\":0}", "publicName"},
                {"{\"publicVisible\":true,\"publicName\":\"  \",\"version\":0}", "publicName"},
                {"{\"publicVisible\":false,\"publicName\":null}", "version"},
                {"{\"publicVisible\":false,\"version\":-1}", "version"},
                {"{\"publicName\":\"Billing\",\"version\":0}", "publicVisible"},
                {"{\"publicVisible\":true,\"publicName\":\"" + "x".repeat(101) + "\",\"version\":0}", "publicName"}
        };
        for (String[] invalidRequest : invalid) {
            mvc.perform(patch("/api/services/{id}/status-page", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content(invalidRequest[0]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors." + invalidRequest[1]).isNotEmpty());
        }
    }

    @Test
    void publicationOfUnknownServiceIsNotFound() throws Exception {
        mvc.perform(patch("/api/services/999999/status-page").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicVisible\":false,\"publicName\":null,\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    private static String withVersion(String body, long version) {
        return body.replace("\"version\":0", "\"version\":" + version);
    }

    private long createService(String name, Long ownerId, String criticality, String targets) throws Exception {
        String response = mvc.perform(post("/api/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, ownerId, criticality, targets)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private AppUser createUser(String username, Role role) {
        return accounts.save(new AppUser(username + "@test.local", "Test " + username, passwordEncoder.encode(PASSWORD), clock.instant()), role);
    }

    private String tokenFor(String username) throws Exception {
        String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", PASSWORD);
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    /** Adds the status page fields (D45) to a body from {@link #body}; both values are raw JSON. */
    private static String withPublication(String body, String publicVisible, String publicName) {
        return body.strip().replaceFirst("}$",
                ", \"publicVisible\": " + publicVisible + ", \"publicName\": " + publicName + "}");
    }

    private static String body(String name, long ownerId, String criticality, String targets) {
        return """
                {"name": "%s", "description": "Test service", "ownerId": %d, "criticality": "%s", "targets": %s, "version":0}
                """.formatted(name, ownerId, criticality, targets);
    }

    private static String targets(int response, int resolution) {
        String one = "{\"responseMinutes\": %d, \"resolutionMinutes\": %d}".formatted(response, resolution);
        return "{\"SEV1\": %s, \"SEV2\": %s, \"SEV3\": %s, \"SEV4\": %s}".formatted(one, one, one, one);
    }
}
