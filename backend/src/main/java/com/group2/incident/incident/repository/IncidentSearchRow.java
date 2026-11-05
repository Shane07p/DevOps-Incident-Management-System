package com.group2.incident.incident.repository;

import java.time.Instant;

public interface IncidentSearchRow {

	Long getIncidentId();

	Long getIncidentNumber();

	String getTitle();

	String getServiceName();

	String getSeverity();

	String getStatus();

	Instant getCreatedAt();

	Boolean getTitleMatch();

	Boolean getDescriptionMatch();

	Boolean getTimelineMatch();
}
