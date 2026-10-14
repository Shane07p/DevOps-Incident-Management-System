package com.group2.incident.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

	List<AuditEntry> findByItemTypeAndItemIdOrderByIdAsc(String itemType, Long itemId);

}
