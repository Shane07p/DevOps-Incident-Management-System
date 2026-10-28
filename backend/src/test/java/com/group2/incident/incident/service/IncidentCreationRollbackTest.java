package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

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
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
	private ServiceRepository serviceRepository;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private AuditService auditService;

	// Outside the test transaction, so createIncident commits or rolls back on its own, as it does in production.
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("TC-FR-02.1c: When audit write fails during incident creation, no incident and no timeline entry are saved")
	void auditFailureRollsBackIncidentAndTimeline() {
		Instant now = clock.instant();
		AppUser user = userRepository.saveAndFlush(new AppUser("rollback_engineer", "Rollback Engineer", "hash", Role.ENGINEER, now));
		ServiceEntity service = new ServiceEntity("RollbackService", "Auth and login", user, Criticality.HIGH, now);
		for (Severity severity : Severity.values()) {
			service.putTarget(severity, 60, 480);
		}
		service = serviceRepository.saveAndFlush(service);

		try {
			long incidentsBefore = incidentRepository.count();
			long timelineBefore = timelineEntryRepository.count();
			// Stub the spy behind the transactional proxy: no transaction is open here, and record() requires one.
			AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
			doThrow(new RuntimeException("Audit database error"))
					.when(spy)
					.record(any(), eq(AuditAction.INCIDENT_CREATED), any(), anyLong(), anyString());

			CreateIncidentRequest request = new CreateIncidentRequest("Login failing", "Timeout during login", service.getId(), Severity.SEV2);

			assertThatThrownBy(() -> incidentService.createIncident(request, user.getId()))
					.isInstanceOf(RuntimeException.class)
					.hasMessage("Audit database error");

			assertThat(incidentRepository.count()).isEqualTo(incidentsBefore);
			assertThat(timelineEntryRepository.count()).isEqualTo(timelineBefore);
		}
		finally {
			serviceRepository.delete(service);
			userRepository.delete(user);
		}
	}
}
