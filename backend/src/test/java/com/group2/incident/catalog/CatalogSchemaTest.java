package com.group2.incident.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database refuses bad catalog rows even if the application check is bypassed. */
@IntegrationTest
class CatalogSchemaTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void resolutionTargetShorterThanResponseIsRefused() {
		long serviceId = newService("Search");

		assertThatThrownBy(() -> jdbc.update(
				"insert into service_sla_target (service_id, severity, response_minutes, resolution_minutes) values (?, 'SEV1', 60, 30)",
				serviceId)).isInstanceOf(DataAccessException.class);
	}

	@Test
	void unknownSeverityIsRefused() {
		long serviceId = newService("Search");

		assertThatThrownBy(() -> jdbc.update(
				"insert into service_sla_target (service_id, severity, response_minutes, resolution_minutes) values (?, 'SEV5', 1, 1)",
				serviceId)).isInstanceOf(DataAccessException.class);
	}

	@Test
	void serviceNameIsUniqueIgnoringCase() {
		assertThatThrownBy(() -> newService("PAYMENTS")).isInstanceOf(DataAccessException.class);
	}

	// One statement per test: after the first error PostgreSQL aborts the test's transaction.
	@Test
	void auditEntriesCannotBeChanged() {
		insertAuditEntry();

		assertThatThrownBy(() -> jdbc.update("update audit_entry set action = 'CHANGED'"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("append-only");
	}

	@Test
	void auditEntriesCannotBeDeleted() {
		insertAuditEntry();

		assertThatThrownBy(() -> jdbc.update("delete from audit_entry"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("append-only");
	}

	private void insertAuditEntry() {
		jdbc.update("insert into audit_entry (actor_id, action, item_type, recorded_at) "
				+ "select id, 'TEST', 'SERVICE', now() from app_user where username = 'admin'");
	}

	private long newService(String name) {
		return jdbc.queryForObject("insert into service (name, owner_id, criticality, created_at, updated_at) "
				+ "select ?, id, 'LOW', now(), now() from app_user where username = 'admin' returning id", Long.class, name);
	}

}
