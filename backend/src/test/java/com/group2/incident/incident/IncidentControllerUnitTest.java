package com.group2.incident.incident;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.common.error.ApiExceptionHandler;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.controller.IncidentController;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.incident.dto.IncidentServiceDto;
import com.group2.incident.incident.dto.IncidentSummaryResponse;
import com.group2.incident.incident.dto.IncidentUserDto;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.Severity;
import com.group2.incident.incident.entity.TimelineEventType;
import com.group2.incident.incident.service.IncidentService;
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
class IncidentControllerUnitTest {

	@Mock
	private IncidentService incidentService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		org.springframework.security.oauth2.jwt.Jwt mockJwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("mock-token")
				.header("alg", "none")
				.subject("2")
				.claim("tv", 1)
				.build();

		mvc = MockMvcBuilders.standaloneSetup(new IncidentController(incidentService))
				.setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
					@Override
					public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
						return parameter.getParameterType().equals(org.springframework.security.oauth2.jwt.Jwt.class);
					}

					@Override
					public Object resolveArgument(org.springframework.core.MethodParameter parameter,
							org.springframework.web.method.support.ModelAndViewContainer mavContainer,
							org.springframework.web.context.request.NativeWebRequest webRequest,
							org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
						return mockJwt;
					}
				})
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("TC-FR-02.1a: Create incident with valid payload returns 201")
	void createIncidentReturns201() throws Exception {
		Instant now = Instant.parse("2026-10-19T10:00:00Z");
		IncidentResponse response = new IncidentResponse(
				7L,
				"INC-7",
				"Checkout failing",
				"Database timeouts",
				new IncidentServiceDto(1L, "Payments"),
				Severity.SEV2,
				IncidentStatus.OPEN,
				new IncidentUserDto(2L, "Dev Engineer"),
				null,
				now,
				60,
				480,
				now.plusSeconds(3600),
				now.plusSeconds(28800),
				List.of(new TimelineEntryResponse(3L, TimelineEventType.INCIDENT_CREATED, "Dev Engineer",
						"Reported as SEV2 on Payments", now))
		);

		when(incidentService.createIncident(any(CreateIncidentRequest.class), any())).thenReturn(response);

		String body = """
				{
					"title": "Checkout failing",
					"description": "Database timeouts",
					"serviceId": 1,
					"severity": "SEV2"
				}
				""";

		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.reference").value("INC-7"))
				.andExpect(jsonPath("$.title").value("Checkout failing"))
				.andExpect(jsonPath("$.service.name").value("Payments"))
				.andExpect(jsonPath("$.severity").value("SEV2"))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.timeline[0].eventType").value("INCIDENT_CREATED"));
	}

	@Test
	@DisplayName("TC-FR-02.1b: Missing required fields return 400 naming the field")
	void missingRequiredFieldsReturn400() throws Exception {
		// Missing title
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":\"desc\",\"serviceId\":1,\"severity\":\"SEV2\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.title").value("Title is required"));

		// Missing description
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"title\",\"serviceId\":1,\"severity\":\"SEV2\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.description").value("Description is required"));

		// Missing serviceId
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"title\",\"description\":\"desc\",\"severity\":\"SEV2\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceId").value("Service is required"));

		// Missing severity
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"title\",\"description\":\"desc\",\"serviceId\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.severity").value("Severity is required"));
	}

	@Test
	@DisplayName("Unknown service throws FieldValidationException which returns 400 naming serviceId")
	void unknownServiceReturns400() throws Exception {
		when(incidentService.createIncident(any(CreateIncidentRequest.class), any()))
				.thenThrow(new FieldValidationException("serviceId", "Service not found"));

		String body = "{\"title\":\"title\",\"description\":\"desc\",\"serviceId\":999,\"severity\":\"SEV2\"}";

		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceId").value("Service not found"));
	}

	@Test
	@DisplayName("TC-FR-04.1a: Severity SEV5 and blank return 400")
	void invalidSeverityReturns400() throws Exception {
		String sev5Body = "{\"title\":\"title\",\"description\":\"desc\",\"serviceId\":1,\"severity\":\"SEV5\"}";
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(sev5Body))
				.andExpect(status().isBadRequest());

		String blankBody = "{\"title\":\"title\",\"description\":\"desc\",\"serviceId\":1,\"severity\":\"\"}";
		mvc.perform(post("/api/incidents").contentType(MediaType.APPLICATION_JSON).content(blankBody))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("GET /api/incidents returns list")
	void listIncidentsReturns200() throws Exception {
		when(incidentService.listIncidents()).thenReturn(List.of(
				new IncidentSummaryResponse(7L, "INC-7", "Checkout failing", "Payments",
						Severity.SEV2, IncidentStatus.OPEN, Instant.now())
		));

		mvc.perform(get("/api/incidents"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].reference").value("INC-7"))
				.andExpect(jsonPath("$[0].serviceName").value("Payments"));
	}

	@Test
	@DisplayName("GET /api/incidents/{id} returns detail")
	void getIncidentReturnsDetail() throws Exception {
		Instant now = Instant.now();
		when(incidentService.getIncident(eq(7L))).thenReturn(
				new IncidentResponse(7L, "INC-7", "Checkout failing", "DB timeout",
						new IncidentServiceDto(1L, "Payments"), Severity.SEV2, IncidentStatus.OPEN,
						null, null, now, 60, 480, now.plusSeconds(3600), now.plusSeconds(28800), List.of())
		);

		mvc.perform(get("/api/incidents/7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.reference").value("INC-7"));
	}

	@Test
	@DisplayName("GET /api/incidents/{id} unknown returns 404")
	void getIncidentNotFoundReturns404() throws Exception {
		when(incidentService.getIncident(eq(999L))).thenThrow(new NotFoundException("Incident 999 not found"));

		mvc.perform(get("/api/incidents/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Incident 999 not found"));
	}
}
