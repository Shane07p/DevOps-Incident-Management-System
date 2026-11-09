package com.group2.incident.audit.entity;

/** Only what Sprint 1 records. A later feature adds its own actions with the code that records them. */
public enum AuditAction {
    USER_CREATED,
    USER_ROLE_CHANGED,
    SERVICE_CREATED,
    SERVICE_UPDATED,
    INCIDENT_CREATED,
    TOOL_REGISTERED,
    TOOL_REVOKED,
    ESCALATION_POLICY_UPDATED,
    ORG_CREATED,
    ON_CALL_SCHEDULE_UPDATED,
    MONITOR_CREATED,
    MONITOR_UPDATED,
    SERVICE_DEPENDENCIES_UPDATED,
    TOKEN_CREATED,
    TOKEN_REVOKED,
    RUNBOOK_CREATED,
    RUNBOOK_UPDATED,
    RUNBOOK_DELETED
}
