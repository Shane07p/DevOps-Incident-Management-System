package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.support.IntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AuthServiceLogoutTest {

	@Autowired
	private AuthService authService;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	// Outside the test transaction, so the demotion really commits between logout's read and write.
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void logoutKeepsARoleChangedMeanwhile() {
		long id = users.save(new AppUser("logout_race", "Logout Race", "hash", Role.ADMIN, clock.instant())).getId();
		TransactionTemplate demotion = new TransactionTemplate(transactionManager);
		demotion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				// Logout's view of the user: still an Admin.
				users.findById(id).orElseThrow();
				demotion.executeWithoutResult(inner -> users.findById(id).orElseThrow().changeRole(Role.VIEWER));
				authService.logout(id);
			});

			AppUser after = users.findById(id).orElseThrow();
			assertThat(after.getRole()).isEqualTo(Role.VIEWER);
			// Both the role change and the logout revoke tokens.
			assertThat(after.getTokenVersion()).isEqualTo(2);
		}
		finally {
			users.deleteById(id);
		}
	}
}
