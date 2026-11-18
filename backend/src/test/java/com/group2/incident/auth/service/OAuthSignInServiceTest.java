package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.dto.OAuthPendingSignupResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.repository.UserIdentityRepository;
import com.group2.incident.common.error.UnauthorizedException;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Sign in with Google and GitHub (D50): matching the person, linking, and one-time codes, against PostgreSQL. */
@IntegrationTest
class OAuthSignInServiceTest {

	@Autowired
	private OAuthSignInService signIn;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private UserIdentityRepository identities;

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private static String codeIn(String path) {
		return path.substring(path.indexOf("code=") + "code=".length());
	}

	private static ProviderAccount google(String subject, String email) {
		return new ProviderAccount(OAuthProvider.GOOGLE, subject, email, true, "New Person");
	}

	// TC-D50a
	@Test
	void aNewVerifiedAccountNamesItsOrgThenSignsUpWithNoPassword() {
		String path = signIn.finish(google("g-new", "New.Person@Example.test"));
		assertThat(path).startsWith("/signup/finish#code=");
		String code = codeIn(path);

		OAuthPendingSignupResponse pending = signIn.pendingSignup(code);
		assertThat(pending.email()).isEqualTo("new.person@example.test");
		assertThat(pending.displayName()).isEqualTo("New Person");
		assertThat(pending.provider()).isEqualTo("google");

		LoginResponse session = signIn.completeSignup(code, "Acme Payments", "Nina Person");
		assertThat(session.org().name()).isEqualTo("Acme Payments");
		assertThat(session.user().role()).isEqualTo(Role.ADMIN);
		assertThat(session.user().displayName()).isEqualTo("Nina Person");
		AppUser user = users.findByEmail("new.person@example.test").orElseThrow();
		assertThat(user.getPasswordHash()).isNull();
		assertThat(identities.findByProviderAndSubject(OAuthProvider.GOOGLE, "g-new")).isPresent();

		// The sign-up code worked once.
		assertThatThrownBy(() -> signIn.completeSignup(code, "Second org", "Nina")).isInstanceOf(UnauthorizedException.class);
	}

	// TC-D50b
	@Test
	void aLinkedAccountSignsInWithACodeThatWorksOnce() {
		signIn.completeSignup(codeIn(signIn.finish(google("g-back", "back@example.test"))), "Back org", "Back");

		String path = signIn.finish(google("g-back", "back@example.test"));
		assertThat(path).startsWith("/auth/callback#code=");
		String code = codeIn(path);

		LoginResponse session = signIn.exchange(code);
		assertThat(session.token()).isNotBlank();
		assertThat(session.org().name()).isEqualTo("Back org");
		assertThatThrownBy(() -> signIn.exchange(code)).isInstanceOf(UnauthorizedException.class);
	}

	// TC-D50c: the provider proved the email; a password set before never was, so it goes and old tokens stop.
	@Test
	void theFirstSignInLinksTheAccountWithThatEmailDropsItsPasswordAndEndsItsSessions() throws Exception {
		TestOrgs.SignedUp ana = orgs.signUp("ana@link.test", "Link org");
		int versionBefore = users.findByEmail("ana@link.test").orElseThrow().getTokenVersion();

		String path = signIn.finish(new ProviderAccount(OAuthProvider.GITHUB, "4242", "ANA@link.test", true, "Ana"));

		assertThat(path).startsWith("/auth/callback#code=");
		AppUser ana2 = users.findByEmail("ana@link.test").orElseThrow();
		assertThat(ana2.getPasswordHash()).isNull();
		assertThat(ana2.getTokenVersion()).isEqualTo(versionBefore + 1);
		assertThat(identities.findByProviderAndSubject(OAuthProvider.GITHUB, "4242")).isPresent();
		mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + ana.adminToken()))
				.andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject(
				"select count(*) from audit_entry where action = 'ACCOUNT_LINKED' and item_id = ?", Integer.class, ana.adminId()))
				.isEqualTo(1);

		LoginResponse session = signIn.exchange(codeIn(path));
		assertThat(session.user().id()).isEqualTo(ana.adminId());
		assertThat(session.org().id()).isEqualTo(ana.orgId());
	}

	// TC-D50d
	@Test
	void anUnverifiedOrMissingEmailIsRefusedAndCreatesNothing() {
		int ticketsBefore = jdbc.queryForObject("select count(*) from oauth_ticket", Integer.class);

		assertThat(signIn.finish(new ProviderAccount(OAuthProvider.GOOGLE, "g-unverified", "nobody@example.test", false, "Nobody")))
				.isEqualTo("/login?oauth=unverified");
		assertThat(signIn.finish(new ProviderAccount(OAuthProvider.GITHUB, "777", null, false, "octo")))
				.isEqualTo("/login?oauth=unverified");

		assertThat(jdbc.queryForObject("select count(*) from oauth_ticket", Integer.class)).isEqualTo(ticketsBefore);
		assertThat(users.findByEmail("nobody@example.test")).isEmpty();
	}

	// TC-D50e
	@Test
	void aDisabledMemberIsRefusedAndTheirAccountIsLeftAsItWas() throws Exception {
		TestOrgs.SignedUp off = orgs.signUp("off@disabled.test", "Disabled org");
		jdbc.update("update membership set enabled = false where user_id = ?", off.adminId());

		assertThat(signIn.finish(google("g-off", "off@disabled.test"))).isEqualTo("/login?oauth=disabled");

		assertThat(identities.findByProviderAndSubject(OAuthProvider.GOOGLE, "g-off")).isEmpty();
		assertThat(users.findByEmail("off@disabled.test").orElseThrow().getPasswordHash()).isNotNull();
	}

	// TC-D50f
	@Test
	void aCodeOfTheWrongKindOrPastItsLifetimeIsRefused() {
		String signupCode = codeIn(signIn.finish(google("g-kind", "kind@example.test")));
		assertThatThrownBy(() -> signIn.exchange(signupCode)).isInstanceOf(UnauthorizedException.class);

		signIn.completeSignup(codeIn(signIn.finish(google("g-kind2", "kind2@example.test"))), "Kind org", "Kind");
		String loginCode = codeIn(signIn.finish(google("g-kind2", "kind2@example.test")));
		assertThatThrownBy(() -> signIn.pendingSignup(loginCode)).isInstanceOf(UnauthorizedException.class);

		jdbc.update("update oauth_ticket set expires_at = now() - interval '1 second' where code_hash = ?",
				OAuthSignInService.hash(loginCode));
		assertThatThrownBy(() -> signIn.exchange(loginCode)).isInstanceOf(UnauthorizedException.class);
		assertThatThrownBy(() -> signIn.exchange("not-a-code")).isInstanceOf(UnauthorizedException.class);
	}

	@Test
	void onlyTheHashOfACodeIsStored() {
		String code = codeIn(signIn.finish(google("g-hash", "hash@example.test")));

		assertThat(jdbc.queryForObject("select count(*) from oauth_ticket where code_hash = ?", Integer.class, code)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from oauth_ticket where code_hash = ?", Integer.class,
				OAuthSignInService.hash(code))).isEqualTo(1);
	}

	@Test
	void withoutANameFromTheProviderTheEmailStandsInAndALongNameIsCut() {
		String unnamed = codeIn(signIn.finish(new ProviderAccount(OAuthProvider.GITHUB, "5150", "octo.cat@example.test", true, null)));
		assertThat(signIn.pendingSignup(unnamed).displayName()).isEqualTo("octo.cat");

		String longName = "N".repeat(150);
		String named = codeIn(signIn.finish(new ProviderAccount(OAuthProvider.GOOGLE, "g-long", "long@example.test", true, longName)));
		assertThat(signIn.pendingSignup(named).displayName()).hasSize(100);
	}
}
