package com.group2.incident.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.support.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dev seed loads only when its location is named explicitly (D27). These tests share the
 * default context and its database: the seed's contents are checked inside the rolled-back test
 * transaction, and the one check that has to commit uses a throwaway schema.
 */
@IntegrationTest
class DevSeedTest {

	private static final String SEED = "db/devdata/R__dev_seed.sql";

	private static final String CHECK_SCHEMA = "dev_seed_check";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Flyway flyway;

	@Test
	void seedIsNotPartOfTheDefaultMigrations() {
		assertThat(flyway.info().all()).extracting(MigrationInfo::getDescription).doesNotContain("dev seed");
	}

	@Test
	void seedIsFoundOnceItsLocationIsAdded() {
		Flyway optedIn = Flyway.configure().configuration(flyway.getConfiguration())
				.locations("classpath:db/migration", "classpath:db/devdata")
				.load();

		assertThat(optedIn.info().pending()).extracting(MigrationInfo::getDescription).containsExactly("dev seed");
	}

	@Test
	void seedCreatesDevOrgWithOneEnabledMemberPerRole() throws IOException {
		runSeed();

		List<String> users = jdbc.queryForList("""
				select u.email || ':' || m.role || ':' || m.enabled || ':' || u.token_version
				from app_user u
				join membership m on m.user_id = u.id
				join organization o on o.id = m.org_id and o.slug = 'dev-org'
				order by u.email""", String.class);

		assertThat(users).containsExactly("admin@settle.local:ADMIN:true:0", "engineer@settle.local:ENGINEER:true:0",
				"viewer@settle.local:VIEWER:true:0");
	}

	@Test
	void seedPasswordIsDevPassw0rd() throws IOException {
		runSeed();
		PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		for (String hash : jdbc.queryForList("select password_hash from app_user where email like '%@settle.local'", String.class)) {
			assertThat(encoder.matches("DevPassw0rd!", hash)).isTrue();
		}
	}

	@Test
	void seedRegistersPaymentsWithTargets() throws IOException {
		runSeed();

		Map<String, Object> service = jdbc.queryForMap("""
				select s.criticality, u.email as owner
				from service s
				join app_user u on u.id = s.owner_id
				join organization o on o.id = s.org_id and o.slug = 'dev-org'
				where s.name = 'Payments'""");
		List<String> targets = jdbc.queryForList("""
				select t.severity || ':' || t.response_minutes || ':' || t.resolution_minutes
				from service_sla_target t join service s on s.id = t.service_id
				join organization o on o.id = s.org_id and o.slug = 'dev-org'
				where s.name = 'Payments' order by t.severity""", String.class);

		assertThat(service).containsEntry("criticality", "CRITICAL").containsEntry("owner", "admin@settle.local");
		assertThat(targets).containsExactly("SEV1:15:240", "SEV2:60:480", "SEV3:240:2880", "SEV4:1440:10080");
	}

	@Test
	void seedIsIdempotent() throws IOException {
		// Only the dev org's rows: other tests may have committed rows in their own orgs.
		String counts = "select (select count(*) from membership m join organization o on o.id = m.org_id where o.slug = 'dev-org')"
				+ " || '/' || (select count(*) from service s join organization o on o.id = s.org_id where o.slug = 'dev-org')"
				+ " || '/' || (select count(*) from service_sla_target t join service s on s.id = t.service_id"
				+ " join organization o on o.id = s.org_id where o.slug = 'dev-org')";
		runSeed();
		String once = jdbc.queryForObject(counts, String.class);

		runSeed();

		assertThat(jdbc.queryForObject(counts, String.class)).isEqualTo(once).isEqualTo("3/1/4");
	}

	/**
	 * Flyway commits, so this runs outside the test transaction, in its own schema, and drops it after.
	 * It uses the application's real ignore-migration-patterns setting.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void databaseThatLoadedTheSeedStillStartsWithoutIt() {
		try {
			Flyway seeded = Flyway.configure().configuration(flyway.getConfiguration())
					.schemas(CHECK_SCHEMA)
					.defaultSchema(CHECK_SCHEMA)
					.locations("classpath:db/migration", "classpath:db/devdata")
					.load();
			MigrateResult result = seeded.migrate();

			Flyway withoutSeed = Flyway.configure().configuration(seeded.getConfiguration())
					.locations("classpath:db/migration")
					.load();
			Flyway withoutSeedOrPattern = Flyway.configure().configuration(withoutSeed.getConfiguration())
					.ignoreMigrationPatterns("*:future")
					.load();

			assertThat(result.migrations).extracting(m -> m.description).contains("dev seed");
			assertThat(withoutSeed.validateWithResult().validationSuccessful).isTrue();
			assertThat(withoutSeedOrPattern.validateWithResult().validationSuccessful).isFalse();
		}
		finally {
			jdbc.execute("drop schema if exists " + CHECK_SCHEMA + " cascade");
		}
	}

	private void runSeed() throws IOException {
		jdbc.execute(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8));
	}

}
