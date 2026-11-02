package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class BootstrapAdminTest {

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	@Test
	void createsTheFirstAdminInAnEmptyDatabase() {
		bootstrap("owner", "Owner-Passw0rd").run(null);

		AppUser admin = users.findByUsername("owner").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(passwordEncoder.matches("Owner-Passw0rd", admin.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingOnceAnyUserExists() {
		users.save(new AppUser("someone", "someone@test.local", "Someone", passwordEncoder.encode("Some-Passw0rd"), Role.VIEWER, clock.instant()));

		bootstrap("owner", "Owner-Passw0rd").run(null);

		assertThat(users.findByUsername("owner")).isEmpty();
	}

	@Test
	void doesNothingWhenNotConfigured() {
		bootstrap("", "").run(null);

		assertThat(users.count()).isZero();
	}

	@Test
	void refusesAShortPassword() {
		assertThatIllegalStateException().isThrownBy(() -> bootstrap("owner", "short").run(null));
	}

	private BootstrapAdmin bootstrap(String username, String password) {
		return new BootstrapAdmin(users, passwordEncoder, clock, username, password);
	}

}
