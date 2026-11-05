package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Outbox;
import com.group2.incident.incident.entity.OutboxStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

	List<Outbox> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, Long aggregateId);

	/** The oldest events in a state, so they leave in the order they were written. */
	List<Outbox> findTop50ByStatusOrderByCreatedAtAscIdAsc(OutboxStatus status);

	// Its own transaction, committed as soon as the broker has confirmed the event.
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Outbox o set o.status = com.group2.incident.incident.entity.OutboxStatus.SENT, o.processedAt = :at where o.id = :id")
	int markSent(@Param("id") long id, @Param("at") Instant at);
}
