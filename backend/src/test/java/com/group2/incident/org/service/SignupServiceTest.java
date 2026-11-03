package com.group2.incident.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.dto.LoginResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.auth.service.AuthService;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.org.dto.SignupRequest;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.entity.Organization;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two sign-ups at once can both find a slug or an email free; the database's unique key then refuses the
 * second insert. The race itself cannot be timed in a test, so the refusal is simulated here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignupServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

	@Mock private AppUserRepository users;
	@Mock private OrganizationRepository orgs;
	@Mock private MembershipRepository memberships;
	@Mock private PasswordEncoder passwordEncoder;
	@Mock private AuditService auditService;
	@Mock private AuthService authService;
	@Mock private TransactionTemplate tx;

	private SignupService signupService;

	private final SignupRequest request = new SignupRequest("ana@same.test", "DevPassw0rd!", "Ana", "Same");

	@BeforeEach
	void setUp() {
		signupService = new SignupService(users, orgs, memberships, passwordEncoder, auditService, authService, tx,
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		when(users.findByEmail("ana@same.test")).thenReturn(Optional.empty());
		when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
		when(users.save(any(AppUser.class))).thenAnswer(inv -> withId(inv.getArgument(0), 11L));
		when(memberships.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));
		when(authService.issueToken(any())).thenReturn(new LoginResponse("token", NOW, null, null));
	}

	@Test
	void aSlugTakenSinceItWasCheckedIsLookedForAgain() {
		when(orgs.existsBySlug("same")).thenReturn(false, true);
		when(orgs.existsBySlug("same-2")).thenReturn(false);
		when(orgs.save(any(Organization.class)))
				.thenThrow(violation("organization_slug_key"))
				.thenAnswer(inv -> withId(inv.getArgument(0), 5L));

		signupService.signup(request);

		ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
		verify(orgs, times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(Organization::getSlug).containsExactly("same", "same-2");
	}

	@Test
	void anEmailTakenSinceItWasCheckedIsA409() {
		when(users.save(any(AppUser.class))).thenThrow(violation("app_user_email_key"));

		assertThatThrownBy(() -> signupService.signup(request))
				.isInstanceOf(ConflictException.class)
				.hasMessage("An account with this email already exists");
	}

	@Test
	void aSlugThatKeepsBeingTakenGivesUpAfterThreeAttempts() {
		when(orgs.existsBySlug(any())).thenReturn(false);
		when(orgs.save(any(Organization.class))).thenThrow(violation("organization_slug_key"));

		assertThatThrownBy(() -> signupService.signup(request)).isInstanceOf(DataIntegrityViolationException.class);
		verify(orgs, times(3)).save(any(Organization.class));
	}

	@Test
	void anyOtherRefusalIsNotRetried() {
		when(orgs.existsBySlug(any())).thenReturn(false);
		when(orgs.save(any(Organization.class))).thenThrow(violation("organization_slug_check"));

		assertThatThrownBy(() -> signupService.signup(request)).isInstanceOf(DataIntegrityViolationException.class);
		verify(orgs, times(1)).save(any(Organization.class));
	}

	// What Spring makes of PostgreSQL refusing an insert on a unique key.
	private static DataIntegrityViolationException violation(String key) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("duplicate key value violates unique constraint \"" + key + "\"",
						new SQLException("duplicate key", "23505"), key));
	}

	private static <T> T withId(T entity, long id) {
		ReflectionTestUtils.setField(entity, "id", id);
		return entity;
	}

}
