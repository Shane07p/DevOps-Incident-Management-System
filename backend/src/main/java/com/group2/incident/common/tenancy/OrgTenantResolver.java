package com.group2.incident.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/** Hands Hibernate the org from {@link OrgContext} when a session opens. */
@Component
public class OrgTenantResolver implements CurrentTenantIdentifierResolver<Long> {

	@Override
	public Long resolveCurrentTenantIdentifier() {
		return OrgContext.current();
	}

	// A session keeps the org it opened with; a thread switching org must start a new transaction.
	@Override
	public boolean validateExistingCurrentSessions() {
		return false;
	}

	@Override
	public boolean isRoot(Long tenantId) {
		return tenantId != null && tenantId == OrgContext.ROOT;
	}

}
