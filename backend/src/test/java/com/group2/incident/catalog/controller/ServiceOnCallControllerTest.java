package com.group2.incident.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.catalog.dto.OnCallScheduleResponse;
import com.group2.incident.catalog.dto.ShiftResponse;
import com.group2.incident.catalog.dto.UpdateOnCallScheduleRequest;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ServiceOnCallControllerTest {

    @Mock
    private OnCallService onCallService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ServiceOnCallController(onCallService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/services/{id}/on-call returns schedule")
    void getScheduleSuccess() throws Exception {
        OnCallScheduleResponse response = new OnCallScheduleResponse(
                1L,
                2L,
                "fallback_lead",
                List.of(new ShiftResponse(10L, 3L, "responder_a", Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-10-19T17:00:00Z")))
        );
        when(onCallService.getSchedule(1L)).thenReturn(response);

        mvc.perform(get("/api/services/1/on-call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(1))
                .andExpect(jsonPath("$.fallbackContactId").value(2))
                .andExpect(jsonPath("$.fallbackContactUsername").value("fallback_lead"))
                .andExpect(jsonPath("$.shifts[0].responderUsername").value("responder_a"));
    }

    @Test
    @DisplayName("GET /api/services/{id}/on-call returns 404 when service not found")
    void getScheduleNotFound() throws Exception {
        when(onCallService.getSchedule(999L)).thenThrow(new NotFoundException("Service 999 not found"));

        mvc.perform(get("/api/services/999/on-call"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/services/{id}/on-call updates schedule successfully")
    void updateScheduleSuccess() throws Exception {
        OnCallScheduleResponse response = new OnCallScheduleResponse(
                1L,
                2L,
                "fallback_lead",
                List.of(new ShiftResponse(10L, 3L, "responder_a", Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-10-19T17:00:00Z")))
        );
        when(onCallService.updateSchedule(eq(1L), any(UpdateOnCallScheduleRequest.class))).thenReturn(response);

        String json = """
                {
                    "fallbackContactId": 2,
                    "shifts": [
                        {
                            "responderId": 3,
                            "startTime": "2026-10-19T09:00:00Z",
                            "endTime": "2026-10-19T17:00:00Z"
                        }
                    ]
                }
                """;

        mvc.perform(put("/api/services/1/on-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(1))
                .andExpect(jsonPath("$.fallbackContactId").value(2));
    }

    @Test
    @DisplayName("PUT /api/services/{id}/on-call returns 400 when shifts overlap")
    void updateScheduleOverlappingShiftsFails() throws Exception {
        when(onCallService.updateSchedule(eq(1L), any(UpdateOnCallScheduleRequest.class)))
                .thenThrow(new FieldValidationException("shifts", "Shifts cannot overlap for the same service"));

        String json = """
                {
                    "fallbackContactId": 2,
                    "shifts": [
                        {
                            "responderId": 3,
                            "startTime": "2026-10-19T09:00:00Z",
                            "endTime": "2026-10-19T17:00:00Z"
                        },
                        {
                            "responderId": 4,
                            "startTime": "2026-10-19T16:00:00Z",
                            "endTime": "2026-10-19T20:00:00Z"
                        }
                    ]
                }
                """;

        mvc.perform(put("/api/services/1/on-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.shifts").value("Shifts cannot overlap for the same service"));
    }
}
