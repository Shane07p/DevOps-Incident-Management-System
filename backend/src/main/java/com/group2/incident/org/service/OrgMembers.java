package com.group2.incident.org.service;

import com.group2.incident.common.tenancy.OrgContext;
import com.group2.incident.org.entity.Membership;
import com.group2.incident.org.repository.MembershipRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * People in the current org. Users are global and {@code @TenantId} does not filter them (D48), so a user
 * id that arrives in a request (an owner, a responder, a fallback contact) is looked up here: someone in
 * another org is not found, exactly like an id that does not exist.
 */
@Service
@RequiredArgsConstructor
public class OrgMembers {

	private final MembershipRepository memberships;

	public Optional<Membership> find(long userId) {
		return memberships.findByUserIdAndOrgId(userId, OrgContext.current());
	}

}
