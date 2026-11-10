package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.incident.dto.CreateIncidentRequest;
import com.group2.incident.incident.dto.IncidentResponse;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
import com.group2.incident.support.TestOrgs;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@IntegrationTest
class IncidentEmbeddingTest {

	@Autowired
	private TestAccounts accounts;

	@Autowired
	private TestOrgs testOrgs;

	@Autowired
	private ServiceRepository serviceRepository;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private IncidentEmbeddingService incidentEmbeddingService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@MockitoBean
	private AiEmbeddingClient aiEmbeddingClient;

	private AppUser reporter;

	private ServiceEntity service;

	@BeforeEach
	void setUp() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		reporter = accounts.create("embedding-reporter@test.local", Role.ENGINEER);
		service = new ServiceEntity("EmbeddingPayments", "Payment processing", reporter, Criticality.HIGH, clock.instant());
		for (Severity severity : Severity.values()) {
			service.putTarget(severity, 15, 240);
		}
		service = serviceRepository.saveAndFlush(service);
	}

	@Test
	@DisplayName("Creating an incident queues the embedding for the background worker instead of blocking the request")
	void creationQueuesEmbeddingForBackgroundWorker() {
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);

		long incidentId = createIncident().id();

		assertThat(jdbcTemplate.queryForObject(
				"select embedding is null from incident where id = ? and org_id = ?", Boolean.class,
				incidentId, reporterOrgId())).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"select embedding_attempts from incident where id = ? and org_id = ?", Integer.class,
				incidentId, reporterOrgId())).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"select embedding_retry_at is not null from incident where id = ? and org_id = ?", Boolean.class,
				incidentId, reporterOrgId())).isTrue();
		verify(aiEmbeddingClient, never()).embedDocument(anyString());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("A failed background retry does not prevent incident creation and schedules the next retry")
	void failedBackgroundRetrySchedulesTheNextAttempt() throws Exception {
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
		when(aiEmbeddingClient.embedDocument(anyString())).thenReturn(Optional.empty());

		TestOrgs.SignedUp org = createRetryOrg();
		try {
			long incidentId = createRetryIncident(org);
			OrgContext.runAsRoot(incidentEmbeddingService::retryPendingEmbeddings);

			assertThat(jdbcTemplate.queryForObject(
					"select embedding is null from incident where id = ? and org_id = ?", Boolean.class,
					incidentId, org.orgId())).isTrue();
			assertThat(jdbcTemplate.queryForObject(
					"select embedding_attempts from incident where id = ? and org_id = ?", Integer.class,
					incidentId, org.orgId())).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"select embedding_retry_at > now() from incident where id = ? and org_id = ?", Boolean.class,
					incidentId, org.orgId())).isTrue();
		}
		finally {
			testOrgs.delete(org.orgId());
		}
	}

	@Test
	@DisplayName("A missing AI URL creates the incident without calling the AI service")
	void disabledAiStillCreatesIncident() {
		when(aiEmbeddingClient.isEnabled()).thenReturn(false);

		long incidentId = createIncident().id();

		assertThat(incidentId).isPositive();
		assertThat(jdbcTemplate.queryForObject(
				"select embedding is null from incident where id = ? and org_id = ?", Boolean.class,
				incidentId, reporterOrgId())).isTrue();
		verify(aiEmbeddingClient, never()).embedDocument(anyString());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("A later successful AI call fills an embedding that was previously unavailable")
	void retryStoresPreviouslyUnavailableEmbedding() throws Exception {
		when(aiEmbeddingClient.isEnabled()).thenReturn(true);
		when(aiEmbeddingClient.embedDocument(anyString())).thenReturn(Optional.empty(), Optional.of(vector()));
		TestOrgs.SignedUp org = createRetryOrg();
		try {
			long incidentId = createRetryIncident(org);
			OrgContext.runAsRoot(incidentEmbeddingService::retryPendingEmbeddings);
			jdbcTemplate.update("update incident set embedding_retry_at = ? where id = ? and org_id = ?",
					Timestamp.from(clock.instant().minusSeconds(1)), incidentId, org.orgId());

			doAnswer(invocation -> {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
				assertThat(jdbcTemplate.update(
						"update incident set embedding_retry_at = now() + interval '10 minutes' where id = ? and org_id = ?",
						incidentId, org.orgId())).isEqualTo(1);
				return Optional.of(vector());
			}).when(aiEmbeddingClient).embedDocument(anyString());

			int processed = OrgContext.runAsRoot(incidentEmbeddingService::retryPendingEmbeddings);

			assertThat(processed).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"select embedding::text from incident where id = ? and org_id = ?", String.class,
					incidentId, org.orgId())).isEqualTo(vectorLiteral());
			assertThat(jdbcTemplate.queryForObject(
					"select embedding_attempts from incident where id = ? and org_id = ?", Integer.class,
					incidentId, org.orgId())).isZero();
			assertThat(jdbcTemplate.queryForObject(
					"select embedding_retry_at is null from incident where id = ? and org_id = ?", Boolean.class,
					incidentId, org.orgId())).isTrue();
		}
		finally {
			testOrgs.delete(org.orgId());
		}
	}

	private IncidentResponse createIncident() {
		return incidentService.createIncident(
				new CreateIncidentRequest(
						"Payment API timeout", "Requests fail under high load", service.getId(), Severity.SEV2),
				reporter.getId());
	}

	private long reporterOrgId() {
		return jdbcTemplate.queryForObject(
				"select org_id from membership where user_id = ?", Long.class, reporter.getId());
	}

	private TestOrgs.SignedUp createRetryOrg() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		return testOrgs.signUp("embedding-retry-" + suffix + "@test.local", "Embedding retry " + suffix);
	}

	private long createRetryIncident(TestOrgs.SignedUp org) throws Exception {
		long serviceId = testOrgs.createService(
				org.adminToken(), "EmbeddingPayments", org.adminId());
		return TestOrgs.id(testOrgs.declareIncident(org.adminToken(), serviceId, "Payment API timeout"));
	}

	private static List<Double> vector() {
		return java.util.Collections.nCopies(AiEmbeddingClient.EMBEDDING_DIMENSIONS, 0.25);
	}

	private static String vectorLiteral() {
		return "[" + String.join(",", java.util.Collections.nCopies(AiEmbeddingClient.EMBEDDING_DIMENSIONS, "0.25")) + "]";
	}
}
