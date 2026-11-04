package com.group2.incident.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditEntry;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.repository.AuditEntryRepository;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ServiceOnCallControllerTest {

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
    private AppUser responder;
    private ServiceEntity service;
    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createUser("admin", Role.ADMIN);
        responder = createUser("responder", Role.ENGINEER);
        createUser("viewer", Role.VIEWER);
        adminToken = tokenFor("admin");
        viewerToken = tokenFor("viewer");

        ServiceEntity payments = new ServiceEntity("Payments", "Payments Service", admin, Criticality.HIGH, clock.instant());
        for (Severity severity : Severity.values()) {
            payments.putTarget(severity, 15, 240);
        }
        service = services.saveAndFlush(payments);
    }

    @Test
    @DisplayName("PUT then GET /api/services/{id}/on-call returns the saved schedule")
    void updateThenGetSchedule() throws Exception {
        mvc.perform(put("/api/services/" + service.getId() + "/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule(admin.getId(), shift(responder.getId(), "09:00", "17:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackContactId").value(admin.getId()))
                .andExpect(jsonPath("$.shifts[0].responderId").value(responder.getId()));

        mvc.perform(get("/api/services/" + service.getId() + "/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(service.getId()))
                .andExpect(jsonPath("$.fallbackContactName").value("admin"))
                .andExpect(jsonPath("$.shifts[0].responderName").value("responder"))
                .andExpect(jsonPath("$.shifts[0].startTime").value("2026-10-19T09:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/services/{id}/on-call returns 404 for an unknown service")
    void getScheduleNotFound() throws Exception {
        mvc.perform(get("/api/services/999999/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/services/{id}/on-call returns 400 when shifts overlap")
    void overlappingShiftsReturn400() throws Exception {
        mvc.perform(put("/api/services/" + service.getId() + "/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule(admin.getId(),
                                shift(responder.getId(), "09:00", "17:00") + "," + shift(admin.getId(), "16:00", "20:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.shifts").value("Shifts cannot overlap for the same service"));
    }

    @Test
    @DisplayName("PUT /api/services/{id}/on-call records an audit entry with the authenticated admin")
    void updateScheduleRecordsAuditEntry() throws Exception {
        mvc.perform(put("/api/services/" + service.getId() + "/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule(admin.getId(), shift(responder.getId(), "09:00", "17:00"))))
                .andExpect(status().isOk());

        List<AuditEntry> entries = auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        );
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
        assertThat(entry.getAction()).isEqualTo(AuditAction.ON_CALL_SCHEDULE_UPDATED);
        assertThat(entry.getItemType()).isEqualTo(AuditItemType.SERVICE);

        // Submitting identical schedule produces no additional audit entry
        mvc.perform(put("/api/services/" + service.getId() + "/on-call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule(admin.getId(), shift(responder.getId(), "09:00", "17:00"))))
                .andExpect(status().isOk());

        assertThat(auditEntries.findByItemTypeAndItemIdOrderByRecordedAtDesc(
                AuditItemType.SERVICE, service.getId()
        )).hasSize(1);
    }

    private static String schedule(long fallbackContactId, String shifts) {
        return "{\"fallbackContactId\":%d,\"shifts\":[%s]}".formatted(fallbackContactId, shifts);
    }

    private static String shift(long responderId, String start, String end) {
        return "{\"responderId\":%d,\"startTime\":\"2026-10-19T%s:00Z\",\"endTime\":\"2026-10-19T%s:00Z\"}"
                .formatted(responderId, start, end);
    }

    private AppUser createUser(String username, Role role) {
        return accounts.save(new AppUser(username + "@test.local", username, passwordEncoder.encode(PASSWORD), clock.instant()), role);
    }

    private String tokenFor(String username) throws Exception {
        String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(username + "@test.local", PASSWORD);
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }
}
