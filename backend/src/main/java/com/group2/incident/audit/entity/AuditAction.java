package com.group2.incident.audit.entity;

/** Only what Sprint 1 records. A later feature adds its own actions with the code that records them. */
public enum AuditAction {
    USER_CREATED,
    USER_ROLE_CHANGED,
    SERVICE_CREATED,
    SERVICE_UPDATED,
    INCIDENT_CREATED
}
