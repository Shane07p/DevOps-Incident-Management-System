package com.group2.incident.incident.repository;

import com.group2.incident.incident.entity.Outbox;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

	List<Outbox> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, Long aggregateId);
}
