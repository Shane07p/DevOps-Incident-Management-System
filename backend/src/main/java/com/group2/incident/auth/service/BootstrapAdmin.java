package com.group2.incident.auth.service;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production starts without the dev seed, so nobody could log in. When BOOTSTRAP_ADMIN_USERNAME and
 * BOOTSTRAP_ADMIN_PASSWORD are set and the database has no users yet, this creates the first admin.
 * Once any user exists it does nothing, so the variables can stay set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BootstrapAdmin implements ApplicationRunner {

	private final AppUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	@Value("${app.bootstrap-admin.username}")
	private final String username;

	@Value("${app.bootstrap-admin.password}")
	private final String password;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (username.isBlank() || password.isBlank()) {
			return;
		}
		if (password.length() < 8 || password.length() > 72) {
			throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be 8 to 72 characters");
		}
		if (users.count() > 0) {
			return;
		}
		users.save(new AppUser(username, username + "@settle.local", "Administrator", passwordEncoder.encode(password), Role.ADMIN, clock.instant()));
		log.info("Created the first admin, {}", username);
	}

}
