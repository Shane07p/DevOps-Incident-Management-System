package com.settle.audit.repository;

import com.settle.audit.entity.AuditEntry;
import com.settle.audit.entity.AuditItemType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long>,
        JpaSpecificationExecutor<AuditEntry> {

	List<AuditEntry> findByItemTypeAndItemIdOrderByRecordedAtDesc(AuditItemType itemType, long itemId);
}
