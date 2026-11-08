package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.entity.AuditAction;
import com.group2.incident.audit.entity.AuditItemType;
import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.dto.ChangeRoleRequest;
import com.group2.incident.auth.dto.CreateUserRequest;
import com.group2.incident.auth.dto.UserResponse;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.common.error.ConflictException;
import com.group2.incident.common.error.NotFoundException;
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import com.group2.incident.org.repository.OrganizationRepository;
import com.group2.incident.support.Members;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-10-20T12:00:00Z");

	private static final long ORG = 7L;

	@Mock
	private AppUserRepository users;

	@Mock
	private MembershipRepository memberships;

	@Mock
	private OrganizationRepository orgs;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private AuditService auditService;

	private UserAdminService userAdminService;

	private AppUser admin;

	private Membership adminMembership;

	@BeforeEach
	void setUp() {
		OrgContext.set(ORG);
		userAdminService = new UserAdminService(users, memberships, orgs, passwordEncoder, auditService, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

		admin = user(1L, "admin@test.local", "Dev Admin");
		adminMembership = Members.of(admin, Role.ADMIN);
	}

	@AfterEach
	void clearOrg() {
		OrgContext.clear();
	}

	@Test
	void listUsersReturnsThisOrgsMembersWithTheirRoles() {
		AppUser user1 = user(10L, "user1@test.local", "User One");
		AppUser user2 = user(20L, "user2@test.local", "User Two");
		when(memberships.findAllByOrgIdOrderByIdAsc(ORG))
				.thenReturn(List.of(Members.of(user1, Role.ENGINEER), Members.of(user2, Role.VIEWER)));

		List<UserResponse> result = userAdminService.listUsers();

		assertThat(result).containsExactly(
				new UserResponse(10L, "user1@test.local", "User One", Role.ENGINEER),
				new UserResponse(20L, "user2@test.local", "User Two", Role.VIEWER));
	}

	@Test
	void createUserSavesThePersonAndAMembershipInTheAdminsOrgAndAudits() {
		CreateUserRequest request = new CreateUserRequest("Asha@Example.com", "Asha Patel", "ValidPassw0rd!", Role.ENGINEER);
		when(users.findByEmail("Asha@Example.com")).thenReturn(Optional.empty());
		when(memberships.findByUserIdAndOrgId(1L, ORG)).thenReturn(Optional.of(adminMembership));
		when(passwordEncoder.encode("ValidPassw0rd!")).thenReturn("{bcrypt}encoded");
		when(users.save(any(AppUser.class))).thenAnswer(inv -> {
			AppUser u = inv.getArgument(0);
			ReflectionTestUtils.setField(u, "id", 42L);
			return u;
		});
		when(memberships.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));

		UserResponse response = userAdminService.createUser(request, 1L);

		assertThat(response).isEqualTo(new UserResponse(42L, "asha@example.com", "Asha Patel", Role.ENGINEER));
		ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
		verify(memberships).save(membership.capture());
		assertThat(membership.getValue().getOrg()).isSameAs(adminMembership.getOrg());
		assertThat(membership.getValue().getUser().getPasswordHash()).isEqualTo("{bcrypt}encoded");
		verify(auditService).record(eq(admin), eq(AuditAction.USER_CREATED), eq(AuditItemType.USER), eq(42L),
				eq("email: asha@example.com, role: ENGINEER"));
	}

	@Test
	void aTakenEmailIsAConflict() {
		CreateUserRequest request = new CreateUserRequest("ASHA@example.com", "Asha Upper", "ValidPassw0rd!", Role.ENGINEER);
		when(users.findByEmail("ASHA@example.com")).thenReturn(Optional.of(user(5L, "asha@example.com", "Asha")));

		assertThatThrownBy(() -> userAdminService.createUser(request, 1L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("An account with this email already exists");
	}

	@Test
	void anAdminOutsideThisOrgIsNotFound() {
		CreateUserRequest request = new CreateUserRequest("asha@example.com", "Asha", "ValidPassw0rd!", Role.ENGINEER);
		when(users.findByEmail("asha@example.com")).thenReturn(Optional.empty());
		when(memberships.findByUserIdAndOrgId(99L, ORG)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userAdminService.createUser(request, 99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("User 99 not found");
	}

	@Test
	void changeRoleUpdatesTheMembershipRevokesTokensAndAudits() {
		lockableOrg();
		AppUser target = user(50L, "target@test.local", "Target User");
		Membership targetMembership = Members.of(target, Role.VIEWER);
		when(memberships.findByUserIdAndOrgId(50L, ORG)).thenReturn(Optional.of(targetMembership));
		when(memberships.findByUserIdAndOrgId(1L, ORG)).thenReturn(Optional.of(adminMembership));

		UserResponse response = userAdminService.changeRole(50L, new ChangeRoleRequest(Role.ADMIN), 1L);

		assertThat(response.role()).isEqualTo(Role.ADMIN);
		assertThat(targetMembership.getRole()).isEqualTo(Role.ADMIN);
		// One SQL increment, never a write of the loaded user (D21).
		verify(users).revokeTokens(50L);
		assertThat(target.getTokenVersion()).isZero();
		verify(auditService).record(eq(admin), eq(AuditAction.USER_ROLE_CHANGED), eq(AuditItemType.USER), eq(50L),
				eq("role: VIEWER → ADMIN"));
	}

	@Test
	void theLastAdminCannotBeDemoted() {
		lockableOrg();
		when(memberships.findByUserIdAndOrgId(1L, ORG)).thenReturn(Optional.of(adminMembership));
		when(memberships.countByOrgIdAndRoleAndEnabledTrue(ORG, Role.ADMIN)).thenReturn(1L);

		assertThatThrownBy(() -> userAdminService.changeRole(1L, new ChangeRoleRequest(Role.ENGINEER), 1L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("An org needs at least one Admin");
		assertThat(adminMembership.getRole()).isEqualTo(Role.ADMIN);
	}

	@Test
	void anAdminMayBeDemotedWhileAnotherAdminRemains() {
		lockableOrg();
		AppUser other = user(2L, "other@test.local", "Other Admin");
		Membership otherMembership = Members.of(other, Role.ADMIN);
		when(memberships.findByUserIdAndOrgId(2L, ORG)).thenReturn(Optional.of(otherMembership));
		when(memberships.findByUserIdAndOrgId(1L, ORG)).thenReturn(Optional.of(adminMembership));
		when(memberships.countByOrgIdAndRoleAndEnabledTrue(ORG, Role.ADMIN)).thenReturn(2L);

		userAdminService.changeRole(2L, new ChangeRoleRequest(Role.ENGINEER), 1L);

		assertThat(otherMembership.getRole()).isEqualTo(Role.ENGINEER);
	}

	@Test
	void aTargetOutsideThisOrgIsNotFound() {
		lockableOrg();
		when(memberships.findByUserIdAndOrgId(999L, ORG)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userAdminService.changeRole(999L, new ChangeRoleRequest(Role.ADMIN), 1L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("User 999 not found");
	}

	@Test
	void aRoleChangeTakesTheOrgsLockBeforeCountingAdmins() {
		when(memberships.findByUserIdAndOrgId(1L, ORG)).thenReturn(Optional.of(adminMembership));
		when(memberships.countByOrgIdAndRoleAndEnabledTrue(ORG, Role.ADMIN)).thenReturn(1L);
		lockableOrg();

		assertThatThrownBy(() -> userAdminService.changeRole(1L, new ChangeRoleRequest(Role.VIEWER), 1L))
				.isInstanceOf(ConflictException.class);

		InOrder order = inOrder(orgs, memberships);
		order.verify(orgs).findLockedById(ORG);
		order.verify(memberships).countByOrgIdAndRoleAndEnabledTrue(ORG, Role.ADMIN);
	}

	private void lockableOrg() {
		when(orgs.findLockedById(ORG)).thenReturn(Optional.of(adminMembership.getOrg()));
	}

	private static AppUser user(long id, String email, String name) {
		AppUser user = new AppUser(email, name, "{bcrypt}hash", FIXED_NOW);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

}
