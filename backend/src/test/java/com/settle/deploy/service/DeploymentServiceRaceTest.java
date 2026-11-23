package com.settle.deploy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.settle.alert.entity.MonitoringTool;
import com.settle.auth.entity.AppUser;
import com.settle.catalog.entity.Criticality;
import com.settle.catalog.entity.ServiceEntity;
import com.settle.catalog.repository.ServiceRepository;
import com.settle.deploy.dto.DeployWebhookRequest;
import com.settle.deploy.dto.DeployWebhookResponse;
import com.settle.deploy.entity.Deployment;
import com.settle.deploy.repository.DeploymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Two identical deploy events at once both pass the duplicate check; the database's unique key stops the second,
 * which then answers as a duplicate of the first instead of failing.
 */
class DeploymentServiceRaceTest {

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

	private static final Instant DEPLOYED = NOW.minusSeconds(120);

	private final DeploymentRepository deployments = mock(DeploymentRepository.class);

	private final ServiceRepository services = mock(ServiceRepository.class);

	private final DeploymentService service = new DeploymentService(deployments, services, Clock.fixed(NOW, ZoneOffset.UTC));

	private final DeployWebhookRequest request = new DeployWebhookRequest("Payments", "v1.4.0", "abc123", "dev", DEPLOYED);

	private ServiceEntity payments;

	@BeforeEach
	void setUp() {
		payments = new ServiceEntity("Payments", null, new AppUser("owner@test.local", "Owner", "hash", NOW), Criticality.HIGH, NOW);
		ReflectionTestUtils.setField(payments, "id", 1L);
		when(services.findByNameIgnoreCaseForUpdate("Payments")).thenReturn(Optional.of(payments));
	}

	@Test
	void theSecondOfTwoIdenticalDeploysAnswersAsADuplicateOfTheFirst() {
		Deployment first = new Deployment(payments, "v1.4.0", "abc123", "dev", DEPLOYED);
		ReflectionTestUtils.setField(first, "id", 41L);
		when(deployments.findByServiceIdAndVersionAndDeployedAt(1L, "v1.4.0", DEPLOYED))
				.thenReturn(Optional.empty(), Optional.of(first));
		when(deployments.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute statement",
				new RuntimeException("duplicate key value violates unique constraint \"uq_deployment_service_version_time\"")));

		DeployWebhookResponse response = service.processDeploy(mock(MonitoringTool.class), request);

		assertThat(response.status()).isEqualTo("DUPLICATE");
		assertThat(response.id()).isEqualTo(41L);
		assertThat(response.service()).isEqualTo("Payments");
	}

	@Test
	void anyOtherDatabaseErrorIsNotTakenForADuplicate() {
		when(deployments.findByServiceIdAndVersionAndDeployedAt(1L, "v1.4.0", DEPLOYED)).thenReturn(Optional.empty());
		when(deployments.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("value too long for type character varying(100)"));

		assertThatThrownBy(() -> service.processDeploy(mock(MonitoringTool.class), request))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("value too long");
	}
}
