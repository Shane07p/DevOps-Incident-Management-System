package com.group2.incident.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.AppService;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.SlaTarget;
import com.group2.incident.catalog.repository.AppServiceRepository;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.entity.Severity;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.incident.service.IncidentService;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@IntegrationTest
class IncidentCreationRollbackTest {

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private TimelineEntryRepository timelineEntryRepository;

	@Autowired
	private AppUserRepository userRepository;

	@Autowired
	private AppServiceRepository serviceRepository;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private AuditService auditService;

	@Test
	@DisplayName("TC-FR-02.1c: When audit write fails during incident creation, no incident and no timeline entry are saved")
	void auditFailureRollsBackIncidentAndTimeline() {
		Instant now = clock.instant();
		AppUser user = userRepository.saveAndFlush(new AppUser("engineer2", "Dev Engineer 2", "hash", Role.ENGINEER, now));
		AppService service = serviceRepository.saveAndFlush(new AppService(
				"AuthService",
				"Auth and login",
				user,
				Criticality.HIGH,
				Map.of(Severity.SEV2, new SlaTarget(60, 480)),
				now
		));

		doThrow(new RuntimeException("Audit database error"))
				.when(auditService)
				.record(any(), anyString(), anyString(), anyLong(), anyString());

		CreateIncidentRequest request = new CreateIncidentRequest(
				"Login failing",
				"Timeout during login",
				service.getId(),
				Severity.SEV2
		);

		assertThatThrownBy(() -> incidentService.createIncident(request, user.getId()))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Audit database error");

		assertThat(incidentRepository.findAll())
				.noneMatch(inc -> "Login failing".equals(inc.getTitle()));
		assertThat(timelineEntryRepository.findAll())
				.noneMatch(te -> "Reported as SEV2 on AuthService".equals(te.getDetails()));
	}
}
