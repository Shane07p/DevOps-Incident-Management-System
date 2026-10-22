package com.group2.incident.auth.service;

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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

	private final AppUserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuditService auditService;

	private final Clock clock;

	@Transactional(readOnly = true)
	public List<UserResponse> listUsers() {
		return users.findAll().stream()
				.map(UserResponse::from)
				.toList();
	}

	@Transactional
	public UserResponse createUser(CreateUserRequest request, long adminUserId) {
		if (users.findByUsername(request.username()).isPresent()) {
			throw new ConflictException("Username '" + request.username() + "' is already taken");
		}

		AppUser admin = users.findById(adminUserId)
				.orElseThrow(() -> new NotFoundException("Admin user " + adminUserId + " not found"));

		String encodedPassword = passwordEncoder.encode(request.password());
		AppUser newUser = new AppUser(
				request.username(),
				request.displayName(),
				encodedPassword,
				request.role(),
				clock.instant()
		);
		AppUser savedUser = users.save(newUser);

		auditService.record(
				admin,
				AuditAction.USER_CREATED,
				AuditItemType.USER,
				savedUser.getId(),
				"username: " + savedUser.getUsername() + ", role: " + savedUser.getRole()
		);

		log.info("Admin {} created user {} with role {}", admin.getUsername(), savedUser.getUsername(), savedUser.getRole());
		return UserResponse.from(savedUser);
	}

	@Transactional
	public UserResponse changeRole(long targetUserId, ChangeRoleRequest request, long adminUserId) {
		AppUser targetUser = users.findById(targetUserId)
				.orElseThrow(() -> new NotFoundException("User " + targetUserId + " not found"));

		AppUser admin = users.findById(adminUserId)
				.orElseThrow(() -> new NotFoundException("Admin user " + adminUserId + " not found"));

		Role oldRole = targetUser.getRole();
		targetUser.changeRole(request.role());

		auditService.record(
				admin,
				AuditAction.USER_ROLE_CHANGED,
				AuditItemType.USER,
				targetUser.getId(),
				"role: " + oldRole + " → " + request.role()
		);

		log.info("Admin {} changed role of user {} from {} to {}",
				admin.getUsername(), targetUser.getUsername(), oldRole, request.role());
		return UserResponse.from(targetUser);
	}

}
