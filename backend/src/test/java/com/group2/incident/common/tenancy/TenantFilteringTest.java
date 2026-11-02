package com.group2.incident.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Criticality;
import com.group2.incident.catalog.entity.ServiceEntity;
import com.group2.incident.catalog.repository.ServiceRepository;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.OrganizationRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import com.group2.incident.support.TestcontainersConfiguration;

/**
 * Proves @TenantId filters queries and findById against PostgreSQL. No test transaction: each
 * TransactionTemplate call opens its own session, so it picks up the org set just before it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantFilteringTest {

	private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

	@Autowired private OrganizationRepository orgs;
	@Autowired private AppUserRepository users;
	@Autowired private ServiceRepository services;
	@Autowired private TransactionTemplate tx;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

	// Unique per run: this test commits its rows.
	private final String suffix = Long.toString(System.nanoTime());
	private Long orgA;
	private Long orgB;
	private Long serviceOfB;

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM service WHERE org_id IN (?, ?)", orgA, orgB);
		jdbc.update("DELETE FROM app_user WHERE email = ?", "tenant-owner-" + suffix + "@test.local");
		jdbc.update("DELETE FROM organization WHERE id IN (?, ?)", orgA, orgB);
		OrgContext.clear();
	}

	@Test
	void anOrgSeesOnlyItsOwnRowsByQueryAndById() {
		tx.execute(status -> {
			orgA = orgs.save(new Organization("Org A", "tenant-a-" + suffix, NOW)).getId();
			orgB = orgs.save(new Organization("Org B", "tenant-b-" + suffix, NOW)).getId();
			return null;
		});
		AppUser owner = tx.execute(status -> users.save(new AppUser("tenant-owner-" + suffix, "tenant-owner-" + suffix + "@test.local", "Owner", "{noop}x",
				com.group2.incident.auth.entity.Role.ADMIN, NOW)));
		OrgContext.runAs(orgA, () -> tx.execute(status -> services.save(new ServiceEntity("Payments", "A", owner, Criticality.HIGH, NOW))));
		serviceOfB = OrgContext.runAs(orgB, () -> tx.execute(status ->
				services.save(new ServiceEntity("Payments", "B", owner, Criticality.HIGH, NOW)).getId()));

		OrgContext.runAs(orgA, () -> tx.execute(status -> {
			assertThat(services.findAll()).extracting(ServiceEntity::getDescription).containsExactly("A");
			assertThat(services.findById(serviceOfB)).isEmpty();
			return null;
		}));
		OrgContext.runAs(OrgContext.NONE, () -> tx.execute(status -> {
			assertThat(services.findAll()).isEmpty();
			return null;
		}));
	}

}
