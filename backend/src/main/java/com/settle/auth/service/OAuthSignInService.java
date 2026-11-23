package com.settle.auth.service;

import com.settle.audit.entity.AuditAction;
import com.settle.audit.entity.AuditItemType;
import com.settle.audit.service.AuditService;
import com.settle.auth.dto.LoginResponse;
import com.settle.auth.dto.OAuthPendingSignupResponse;
import com.settle.auth.entity.AppUser;
import com.settle.auth.entity.OAuthTicket;
import com.settle.auth.entity.UserIdentity;
import com.settle.auth.repository.AppUserRepository;
import com.settle.auth.repository.OAuthTicketRepository;
import com.settle.auth.repository.PersonalAccessTokenRepository;
import com.settle.auth.repository.UserIdentityRepository;
import com.settle.common.error.UnauthorizedException;
import com.settle.common.tenancy.OrgContext;
import com.settle.org.entity.Membership;
import com.settle.org.repository.MembershipRepository;
import com.settle.org.service.SignupService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a finished Google or GitHub sign-in into a Settle session (D50).
 *
 * <p>The provider's own id finds the person. The first time, a person with the same email is the same person:
 * the provider has verified that email, while Settle never did, so a password set before is dropped, and every
 * session and personal access token issued so far stops working. Nobody with that email yet means a new person, who names their org first.
 *
 * <p>The browser never gets a token in a URL: it gets a one-time code in the URL fragment and swaps it with a POST.
 */
@Service
@RequiredArgsConstructor
public class OAuthSignInService {

	static final Duration LOGIN_CODE_LIFETIME = Duration.ofMinutes(2);
	static final Duration SIGNUP_CODE_LIFETIME = Duration.ofMinutes(30);
	private static final String EXPIRED = "This sign-in link has expired or was already used. Sign in again.";
	private static final int NAME_LIMIT = 100;

	private final AppUserRepository users;
	private final UserIdentityRepository identities;
	private final OAuthTicketRepository tickets;
	private final PersonalAccessTokenRepository accessTokens;
	private final MembershipRepository memberships;
	private final AuditService auditService;
	private final AuthService authService;
	private final SignupService signupService;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	/** Where a resolved sign-in goes next, and the org to record a newly linked account in. */
	private record Outcome(String path, Membership linkedIn) {
	}

	/**
	 * Records a finished provider sign-in and says where to send the browser, as a path on the public URL: the
	 * callback page with a code, the sign-up page with a code, or the sign-in page with a reason.
	 */
	public String finish(ProviderAccount account) {
		if (!account.emailVerified() || account.email() == null || account.email().isBlank()) {
			return "/login?oauth=unverified";
		}
		Outcome outcome = tx.execute(status -> resolve(account));
		if (outcome.linkedIn() != null) {
			Membership membership = outcome.linkedIn();
			OrgContext.runAs(membership.getOrg().getId(), () -> tx.execute(status -> auditService.record(membership.getUser(),
					AuditAction.ACCOUNT_LINKED, AuditItemType.USER, membership.getUser().getId(),
					"provider: " + account.provider().registrationId())));
		}
		return outcome.path();
	}

	private Outcome resolve(ProviderAccount account) {
		Optional<UserIdentity> identity = identities.findByProviderAndSubject(account.provider(), account.subject());
		AppUser user = identity.map(UserIdentity::getUser).orElseGet(() -> users.findByEmail(account.email()).orElse(null));
		if (user == null) {
			return new Outcome("/signup/finish#code=" + issue(OAuthTicket.Kind.SIGNUP, null, account), null);
		}
		Optional<Membership> membership = memberships.findFirstByUserIdAndEnabledTrue(user.getId());
		if (membership.isEmpty()) {
			return new Outcome("/login?oauth=disabled", null);
		}
		Membership linkedIn = null;
		if (identity.isEmpty()) {
			identities.save(new UserIdentity(user, account.provider(), account.subject(), user.getEmail(), clock.instant()));
			if (user.getPasswordHash() != null) {
				users.dropPasswordAndRevokeTokens(user.getId());
			}
			// Whoever held the account before the email was proved may have made access tokens; they stop too.
			accessTokens.revokeAllOfUser(user.getId(), clock.instant());
			linkedIn = membership.get();
		}
		return new Outcome("/auth/callback#code=" + issue(OAuthTicket.Kind.LOGIN, user, account), linkedIn);
	}

	private String issue(OAuthTicket.Kind kind, AppUser user, ProviderAccount account) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		Duration lifetime = kind == OAuthTicket.Kind.LOGIN ? LOGIN_CODE_LIFETIME : SIGNUP_CODE_LIFETIME;
		tickets.save(new OAuthTicket(hash(code), kind, user, account.provider(), account.subject(),
				account.email().trim().toLowerCase(Locale.ROOT), startingName(account), now, now.plus(lifetime)));
		return code;
	}

	private static String startingName(ProviderAccount account) {
		String name = account.displayName() == null || account.displayName().isBlank()
				? account.email().substring(0, account.email().indexOf('@') < 0 ? account.email().length() : account.email().indexOf('@'))
				: account.displayName().trim();
		return name.length() > NAME_LIMIT ? name.substring(0, NAME_LIMIT) : name;
	}

	/** Swaps a LOGIN code for a session, once. */
	@Transactional
	public LoginResponse exchange(String code) {
		OAuthTicket ticket = claim(code, OAuthTicket.Kind.LOGIN);
		Membership membership = memberships.findFirstByUserIdAndEnabledTrue(ticket.getUser().getId())
				.orElseThrow(() -> new UnauthorizedException(EXPIRED));
		return authService.issueToken(membership);
	}

	/** The details behind a SIGNUP code, without using it up. */
	@Transactional(readOnly = true)
	public OAuthPendingSignupResponse pendingSignup(String code) {
		OAuthTicket ticket = valid(code, OAuthTicket.Kind.SIGNUP);
		return new OAuthPendingSignupResponse(ticket.getEmail(), ticket.getDisplayName(), ticket.getProvider().registrationId());
	}

	/** Uses a SIGNUP code: creates the person (with no password), their provider account, the org, and the session. */
	public LoginResponse completeSignup(String code, String orgName, String displayName) {
		OAuthTicket ticket = tx.execute(status -> claim(code, OAuthTicket.Kind.SIGNUP));
		return signupService.signupWithProvider(ticket.getEmail(), displayName, orgName, ticket.getProvider(), ticket.getSubject());
	}

	private OAuthTicket claim(String code, OAuthTicket.Kind kind) {
		OAuthTicket ticket = valid(code, kind);
		if (tickets.claim(ticket.getId(), clock.instant()) != 1) {
			throw new UnauthorizedException(EXPIRED);
		}
		return ticket;
	}

	private OAuthTicket valid(String code, OAuthTicket.Kind kind) {
		return tickets.findByCodeHash(hash(code))
				.filter(ticket -> ticket.getKind() == kind)
				.filter(ticket -> ticket.getUsedAt() == null && ticket.getExpiresAt().isAfter(clock.instant()))
				.orElseThrow(() -> new UnauthorizedException(EXPIRED));
	}

	static String hash(String code) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing from this JVM", e);
		}
	}
}
