package com.group2.incident.audit;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

	private final AuditEntryRepository entries;

	private final Clock clock;

	AuditService(AuditEntryRepository entries, Clock clock) {
		this.entries = entries;
		this.clock = clock;
	}

	/**
	 * MANDATORY: an audit entry only exists inside the transaction of the change it describes, so
	 * the two commit or roll back together.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long actorId, String action, String itemType, Long itemId, String details) {
		entries.save(new AuditEntry(actorId, action, itemType, itemId, details, clock.instant()));
	}

}
