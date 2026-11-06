package com.group2.incident.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Migrates a separate schema to V9, adds rows, then runs V10 and checks where the rows went. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrganizationsMigrationTest {

	@Autowired private DataSource dataSource;

	@Test
	void existingRowsMoveIntoDevOrg() {
		String schema = "migration_check";
		Flyway.configure().dataSource(dataSource).schemas(schema).target("9").cleanDisabled(false).load().migrate();
		// Schema-qualified names: a SET search_path would stay on the pooled connection.
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update("INSERT INTO " + schema + ".app_user (username, display_name, password_hash, role, created_at) VALUES ('Admin', 'Admin', 'x', 'ADMIN', now())");
		jdbc.update("INSERT INTO " + schema + ".service (name, owner_id, criticality, created_at, updated_at) SELECT 'Payments', id, 'HIGH', now(), now() FROM " + schema + ".app_user");

		Flyway.configure().dataSource(dataSource).schemas(schema).target("10").load().migrate();

		assertThat(jdbc.queryForObject("SELECT slug FROM " + schema + ".organization WHERE id = 1", String.class)).isEqualTo("dev-org");
		assertThat(jdbc.queryForObject("SELECT email FROM " + schema + ".app_user", String.class)).isEqualTo("admin@settle.local");
		assertThat(jdbc.queryForObject("SELECT role FROM " + schema + ".membership", String.class)).isEqualTo("ADMIN");
		assertThat(jdbc.queryForObject("SELECT org_id FROM " + schema + ".service", Long.class)).isEqualTo(1L);
		// The next org created after the migration does not collide with id 1.
		assertThat(jdbc.queryForObject("INSERT INTO " + schema + ".organization (name, slug, created_at) VALUES ('Next', 'next', now()) RETURNING id", Long.class)).isEqualTo(2L);
		Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load().clean();
	}

	@Test
	void anEmptyDatabaseGetsNoOrg() {
		String schema = "migration_empty";
		Flyway.configure().dataSource(dataSource).schemas(schema).target("10").load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".organization", Long.class)).isZero();
		Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load().clean();
	}

}
