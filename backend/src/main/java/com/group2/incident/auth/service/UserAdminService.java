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
import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
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

	private final MembershipRepository memberships;

	private final PasswordEncoder passwordEncoder;

	private final AuditService auditService;

	private final Clock clock;

	@Transactional(readOnly = true)
	public List<UserResponse> listUsers() {
		return memberships.findAllByOrgIdOrderByIdAsc(OrgContext.current()).stream().map(UserResponse::from).toList();
	}

	/** Adds a person to the Admin's org. One org per person for now (D48), so a taken email is a conflict. */
	@Transactional
	public UserResponse createUser(CreateUserRequest request, long adminUserId) {
		if (users.findByEmail(request.email()).isPresent()) {
			throw new ConflictException("An account with this email already exists");
		}
		Membership admin = member(adminUserId);
		AppUser user = users.save(new AppUser(request.email(), request.displayName(), passwordEncoder.encode(request.password()), clock.instant()));
		Membership membership = memberships.save(new Membership(admin.getOrg(), user, request.role(), clock.instant()));
		auditService.record(admin.getUser(), AuditAction.USER_CREATED, AuditItemType.USER, user.getId(),
				"email: " + user.getEmail() + ", role: " + membership.getRole());
		log.info("Admin {} added user {} with role {}", adminUserId, user.getId(), membership.getRole());
		return UserResponse.from(membership);
	}

	/** Changes a member's role and ends their sessions. An org always keeps at least one enabled Admin. */
	@Transactional
	public UserResponse changeRole(long targetUserId, ChangeRoleRequest request, long adminUserId) {
		Membership target = member(targetUserId);
		Membership admin = member(adminUserId);
		Role oldRole = target.getRole();
		if (oldRole == Role.ADMIN && request.role() != Role.ADMIN
				&& memberships.countByOrgIdAndRoleAndEnabledTrue(OrgContext.current(), Role.ADMIN) <= 1) {
			throw new ConflictException("An org needs at least one Admin");
		}
		target.changeRole(request.role());
		target.getUser().revokeTokens();
		auditService.record(admin.getUser(), AuditAction.USER_ROLE_CHANGED, AuditItemType.USER, target.getUser().getId(),
				"role: " + oldRole + " → " + request.role());
		log.info("Admin {} changed the role of user {} from {} to {}", adminUserId, targetUserId, oldRole, request.role());
		return UserResponse.from(target);
	}

	private Membership member(long userId) {
		return memberships.findByUserIdAndOrgId(userId, OrgContext.current())
				.orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
	}

}
