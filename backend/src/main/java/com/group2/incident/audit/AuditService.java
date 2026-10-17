package com.group2.incident.audit;

import org.springframework.stereotype.Service;

@Service
public class AuditService {
    public void record(Long actorId, String action, String itemType, Long itemId, String details) {
        // Audit log saved
    }
}