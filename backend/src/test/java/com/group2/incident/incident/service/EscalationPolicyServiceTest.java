package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.error.FieldValidationException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.incident.dto.EscalationPolicyResponse;
import com.group2.incident.incident.dto.EscalationStepRequest;
import com.group2.incident.incident.dto.UpdateEscalationPolicyRequest;
import com.group2.incident.incident.entity.EscalationPolicy;
import com.group2.incident.incident.entity.EscalationStep;
import com.group2.incident.incident.repository.EscalationPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationPolicyServiceTest {

	private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");

	@Mock
	private EscalationPolicyRepository escalationPolicyRepository;

	@Mock
	private ServiceRepository serviceRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private AuditService auditService;

	private Clock fixedClock;
	private EscalationPolicyService service;

	private AppUser admin;
	private AppUser responder1;
	private AppUser responder2;
	private ServiceEntity serviceEntity;

	@BeforeEach
	void setUp() {
		fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
		service = new EscalationPolicyService(
				escalationPolicyRepository,
				serviceRepository,
				appUserRepository,
				auditService,
				fixedClock
		);

		admin = new AppUser("admin", "Admin User", "hash", Role.ADMIN, T0);
		responder1 = new AppUser("resp1", "Responder 1", "hash", Role.ENGINEER, T0);
		responder2 = new AppUser("resp2", "Responder 2", "hash", Role.ENGINEER, T0);

		serviceEntity = new ServiceEntity("OrderService", "Orders", admin, Criticality.HIGH, T0);
	}

	@Test
	@DisplayName("getPolicy returns response when service and policy exist")
	void getPolicySuccess() {
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(serviceEntity));

		EscalationPolicy policy = new EscalationPolicy(serviceEntity, T0);
		policy.replaceSteps(List.of(new EscalationStep(1, responder1, 15)), T0);
		when(escalationPolicyRepository.findByServiceId(1L)).thenReturn(Optional.of(policy));

		EscalationPolicyResponse response = service.getPolicy(1L);

		assertThat(response.serviceId()).isEqualTo(serviceEntity.getId());
		assertThat(response.steps()).hasSize(1);
		assertThat(response.steps().get(0).responderDisplayName()).isEqualTo(responder1.getDisplayName());
	}

	@Test
	@DisplayName("getPolicy throws NotFoundException when service does not exist")
	void getPolicyServiceNotFound() {
		when(serviceRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getPolicy(999L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Service 999 not found");
	}

	@Test
	@DisplayName("getPolicy throws NotFoundException when policy does not exist")
	void getPolicyPolicyNotFound() {
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(serviceEntity));
		when(escalationPolicyRepository.findByServiceId(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getPolicy(1L))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("Escalation policy not found");
	}

	@Test
	@DisplayName("updatePolicy saves valid policy and records audit entry")
	void updatePolicySuccess() {
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(serviceEntity));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(responder1));
		when(appUserRepository.findById(20L)).thenReturn(Optional.of(responder2));
		when(appUserRepository.findById(1L)).thenReturn(Optional.of(admin));
		when(escalationPolicyRepository.findByServiceId(1L)).thenReturn(Optional.empty());
		when(escalationPolicyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		UpdateEscalationPolicyRequest request = new UpdateEscalationPolicyRequest(List.of(
				new EscalationStepRequest(1, 10L, 15),
				new EscalationStepRequest(2, 20L, 30)
		));

		EscalationPolicyResponse response = service.updatePolicy(1L, request, 1L);

		assertThat(response.steps()).hasSize(2);
		assertThat(response.steps().get(0).stepNumber()).isEqualTo(1);
		assertThat(response.steps().get(1).stepNumber()).isEqualTo(2);

		verify(auditService).record(
				eq(admin),
				eq(AuditAction.ESCALATION_POLICY_UPDATED),
				eq(AuditItemType.SERVICE),
				eq(1L),
				any()
		);
	}

	@Test
	@DisplayName("updatePolicy rejects non-sequential step numbers")
	void updatePolicyRejectsNonSequentialSteps() {
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(serviceEntity));

		UpdateEscalationPolicyRequest request = new UpdateEscalationPolicyRequest(List.of(
				new EscalationStepRequest(2, 10L, 15) // missing step 1
		));

		assertThatThrownBy(() -> service.updatePolicy(1L, request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("Step numbers must be sequential starting at 1");
	}

	@Test
	@DisplayName("updatePolicy rejects wait minutes <= 0")
	void updatePolicyRejectsZeroWaitMinutes() {
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(serviceEntity));

		UpdateEscalationPolicyRequest request = new UpdateEscalationPolicyRequest(List.of(
				new EscalationStepRequest(1, 10L, 0)
		));

		assertThatThrownBy(() -> service.updatePolicy(1L, request, 1L))
				.isInstanceOf(FieldValidationException.class)
				.hasMessageContaining("Wait minutes must be greater than zero");
	}
}
