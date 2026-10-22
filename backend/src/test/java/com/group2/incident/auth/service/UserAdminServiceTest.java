package com.group2.incident.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-10-20T12:00:00Z");

	@Mock
	private AppUserRepository users;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private AuditService auditService;

	private Clock clock;

	private UserAdminService userAdminService;

	private AppUser admin;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		userAdminService = new UserAdminService(users, passwordEncoder, auditService, clock);

		admin = new AppUser("admin", "Dev Admin", "{bcrypt}hash", Role.ADMIN, FIXED_NOW);
		ReflectionTestUtils.setField(admin, "id", 1L);
	}

	@Test
	void listUsersReturnsAllUsersWithoutSensitiveFields() {
		AppUser user1 = new AppUser("user1", "User One", "{bcrypt}hash1", Role.ENGINEER, FIXED_NOW);
		ReflectionTestUtils.setField(user1, "id", 10L);
		AppUser user2 = new AppUser("user2", "User Two", "{bcrypt}hash2", Role.VIEWER, FIXED_NOW);
		ReflectionTestUtils.setField(user2, "id", 20L);

		when(users.findAll()).thenReturn(List.of(user1, user2));

		List<UserResponse> result = userAdminService.listUsers();

		assertThat(result).hasSize(2);
		assertThat(result.get(0)).isEqualTo(new UserResponse(10L, "user1", "User One", Role.ENGINEER));
		assertThat(result.get(1)).isEqualTo(new UserResponse(20L, "user2", "User Two", Role.VIEWER));
	}

	@Test
	void createUserSuccessfullySavesAndAudits() {
		CreateUserRequest request = new CreateUserRequest("asha", "Asha Patel", "ValidPassw0rd!", Role.ENGINEER);

		when(users.findByUsername("asha")).thenReturn(Optional.empty());
		when(users.findById(1L)).thenReturn(Optional.of(admin));
		when(passwordEncoder.encode("ValidPassw0rd!")).thenReturn("{bcrypt}encoded");
		when(users.save(any(AppUser.class))).thenAnswer(inv -> {
			AppUser u = inv.getArgument(0);
			ReflectionTestUtils.setField(u, "id", 42L);
			return u;
		});

		UserResponse response = userAdminService.createUser(request, 1L);

		assertThat(response.id()).isEqualTo(42L);
		assertThat(response.username()).isEqualTo("asha");
		assertThat(response.displayName()).isEqualTo("Asha Patel");
		assertThat(response.role()).isEqualTo(Role.ENGINEER);

		ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
		verify(users).save(userCaptor.capture());
		AppUser saved = userCaptor.getValue();
		assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}encoded");
		assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);

		verify(auditService).record(
				eq(admin),
				eq(AuditAction.USER_CREATED),
				eq(AuditItemType.USER),
				eq(42L),
				eq("username: asha, role: ENGINEER")
		);
	}

	@Test
	void createUserThrowsConflictExceptionWhenUsernameAlreadyExists() {
		CreateUserRequest request = new CreateUserRequest("ASHA", "Asha Upper", "ValidPassw0rd!", Role.ENGINEER);
		AppUser existing = new AppUser("asha", "Asha Patel", "{bcrypt}hash", Role.ENGINEER, FIXED_NOW);

		when(users.findByUsername("ASHA")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> userAdminService.createUser(request, 1L))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Username 'ASHA' is already taken");
	}

	@Test
	void createUserThrowsNotFoundExceptionWhenAdminActorDoesNotExist() {
		CreateUserRequest request = new CreateUserRequest("asha", "Asha", "ValidPassw0rd!", Role.ENGINEER);

		when(users.findByUsername("asha")).thenReturn(Optional.empty());
		when(users.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userAdminService.createUser(request, 99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Admin user 99 not found");
	}

	@Test
	void changeRoleUpdatesRoleIncrementsTokenVersionAndAudits() {
		AppUser target = new AppUser("target", "Target User", "{bcrypt}hash", Role.VIEWER, FIXED_NOW);
		ReflectionTestUtils.setField(target, "id", 50L);
		int initialTokenVersion = target.getTokenVersion();

		when(users.findById(50L)).thenReturn(Optional.of(target));
		when(users.findById(1L)).thenReturn(Optional.of(admin));

		ChangeRoleRequest request = new ChangeRoleRequest(Role.ADMIN);
		UserResponse response = userAdminService.changeRole(50L, request, 1L);

		assertThat(response.role()).isEqualTo(Role.ADMIN);
		assertThat(target.getRole()).isEqualTo(Role.ADMIN);
		assertThat(target.getTokenVersion()).isEqualTo(initialTokenVersion + 1);

		verify(auditService).record(
				eq(admin),
				eq(AuditAction.USER_ROLE_CHANGED),
				eq(AuditItemType.USER),
				eq(50L),
				eq("role: VIEWER → ADMIN")
		);
	}

	@Test
	void changeRoleThrowsNotFoundExceptionWhenTargetUserNotFound() {
		when(users.findById(999L)).thenReturn(Optional.empty());

		ChangeRoleRequest request = new ChangeRoleRequest(Role.ADMIN);

		assertThatThrownBy(() -> userAdminService.changeRole(999L, request, 1L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("User 999 not found");
	}

	@Test
	void changeRoleThrowsNotFoundExceptionWhenAdminNotFound() {
		AppUser target = new AppUser("target", "Target User", "{bcrypt}hash", Role.VIEWER, FIXED_NOW);
		when(users.findById(50L)).thenReturn(Optional.of(target));
		when(users.findById(99L)).thenReturn(Optional.empty());

		ChangeRoleRequest request = new ChangeRoleRequest(Role.ADMIN);

		assertThatThrownBy(() -> userAdminService.changeRole(50L, request, 99L))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Admin user 99 not found");
	}
}
