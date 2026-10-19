package com.group2.incident.audit.repository;

import com.group2.incident.audit.entity.AuditEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findByItemTypeAndItemIdOrderByRecordedAtAsc(String itemType, long itemId);
}
