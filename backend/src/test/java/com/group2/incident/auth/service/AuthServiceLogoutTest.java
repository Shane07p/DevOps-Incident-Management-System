package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestAccounts;
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
	private TestAccounts accounts;

	@Autowired
	private AuthService authService;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private MembershipRepository memberships;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	// Outside the test transaction, so the demotion really commits between logout's read and write.
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void logoutKeepsARoleChangedMeanwhile() {
		long org = OrgContext.current();
		long id = accounts.save(new AppUser("logout_race@test.local", "Logout Race", "hash", clock.instant()), Role.ADMIN).getId();
		TransactionTemplate demotion = new TransactionTemplate(transactionManager);
		demotion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				// Logout's view of the user: still an Admin.
				users.findById(id).orElseThrow();
				demotion.executeWithoutResult(inner -> {
					Membership membership = memberships.findByUserIdAndOrgId(id, org).orElseThrow();
					membership.changeRole(Role.VIEWER);
					membership.getUser().revokeTokens();
				});
				authService.logout(id);
			});

			AppUser after = users.findById(id).orElseThrow();
			assertThat(memberships.findByUserIdAndOrgId(id, org).orElseThrow().getRole()).isEqualTo(Role.VIEWER);
			// Both the role change and the logout revoke tokens.
			assertThat(after.getTokenVersion()).isEqualTo(2);
		}
		finally {
			memberships.delete(memberships.findByUserIdAndOrgId(id, org).orElseThrow());
			users.deleteById(id);
		}
	}
}
