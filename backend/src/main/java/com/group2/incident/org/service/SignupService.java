package com.group2.incident.org.service;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.service.AuthService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.UniqueKeys;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.dto.SignupRequest;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the person, the org, and an Admin membership in one transaction, then records the new org
 * in its own audit log. The audit entry is a second transaction because Hibernate fixes a session's
 * org when it opens, before the org exists.
 */
@Service
@RequiredArgsConstructor
public class SignupService {

	private final AppUserRepository users;
	private final OrganizationRepository orgs;
	private final MembershipRepository memberships;
	private final PasswordEncoder passwordEncoder;
	private final AuditService auditService;
	private final AuthService authService;
	private final TransactionTemplate tx;
	private final Clock clock;

	// Two sign-ups at once can both find a slug free; the second insert then breaks its unique key.
	private static final int SLUG_ATTEMPTS = 3;

	public LoginResponse signup(SignupRequest request) {
		Membership membership = createRetryingTakenSlugs(request);
		OrgContext.runAs(membership.getOrg().getId(), () -> tx.execute(status -> {
			auditService.record(membership.getUser(), AuditAction.ORG_CREATED, AuditItemType.ORG, membership.getOrg().getId(),
					"name: " + membership.getOrg().getName() + ", slug: " + membership.getOrg().getSlug());
			return null;
		}));
		return authService.issueToken(membership);
	}

	/**
	 * Creates the person, the org, and the membership in a new transaction. A slug taken since it was checked
	 * is looked for again in another transaction; an email taken since it was checked is the usual 409.
	 */
	private Membership createRetryingTakenSlugs(SignupRequest request) {
		for (int attempt = 1; ; attempt++) {
			try {
				return tx.execute(status -> create(request));
			}
			catch (DataIntegrityViolationException e) {
				if (UniqueKeys.violated(e, "app_user_email_key")) {
					throw new ConflictException("An account with this email already exists");
				}
				if (!UniqueKeys.violated(e, "organization_slug_key") || attempt == SLUG_ATTEMPTS) {
					throw e;
				}
			}
		}
	}

	private Membership create(SignupRequest request) {
		if (users.findByEmail(request.email()).isPresent()) {
			throw new ConflictException("An account with this email already exists");
		}
		Instant now = clock.instant();
		AppUser user = users.save(new AppUser(request.email(), request.displayName().trim(), passwordEncoder.encode(request.password()), now));
		Organization org = orgs.save(new Organization(request.orgName().trim(), freeSlug(request.orgName()), now));
		return memberships.save(new Membership(org, user, Role.ADMIN, now));
	}

	private String freeSlug(String name) {
		String base = Slugs.from(name);
		String slug = base;
		for (int n = 2; orgs.existsBySlug(slug); n++) {
			slug = base + "-" + n;
		}
		return slug;
	}

}
