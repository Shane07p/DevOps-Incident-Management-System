package com.group2.incident.support;

import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Accounts in the current test org (D48), and their login tokens. Registered by {@link IntegrationTest}. */
public class TestAccounts {

	public static final String PASSWORD = "DevPassw0rd!";

	private final AppUserRepository users;
	private final MembershipRepository memberships;
	private final OrganizationRepository orgs;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final MockMvc mvc;

	public TestAccounts(AppUserRepository users, MembershipRepository memberships, OrganizationRepository orgs,
			PasswordEncoder passwordEncoder, Clock clock, MockMvc mvc) {
		this.users = users;
		this.memberships = memberships;
		this.orgs = orgs;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.mvc = mvc;
	}

	/** A person with the dev password, a member of the current org with {@code role}. */
	public AppUser create(String email, Role role) {
		return save(new AppUser(email, displayName(email), passwordEncoder.encode(PASSWORD), clock.instant()), role);
	}

	/** Saves {@code user} and makes them a member of the current org with {@code role}. */
	public AppUser save(AppUser user, Role role) {
		AppUser saved = users.saveAndFlush(user);
		memberships.saveAndFlush(new Membership(orgs.getReferenceById(OrgContext.current()), saved, role, clock.instant()));
		return saved;
	}

	/** The membership of {@code user} in the current org. */
	public Membership membership(AppUser user) {
		return memberships.findByUserIdAndOrgId(user.getId(), OrgContext.current()).orElseThrow();
	}

	public String token(String email) throws Exception {
		return token(email, PASSWORD);
	}

	public String token(String email, String password) throws Exception {
		String body = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	private static String displayName(String email) {
		return email.substring(0, email.indexOf('@'));
	}

}
