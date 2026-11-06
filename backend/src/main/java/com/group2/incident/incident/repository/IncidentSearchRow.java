package com.group2.incident.incident.repository;

import java.time.Instant;

public interface IncidentSearchRow {

	/** Returns the database ID of the matching incident. */
	Long getIncidentId();

	/** Returns the organization's incident number. */
	Long getIncidentNumber();

	/** Returns the service ID associated with the incident. */
	Long getServiceId();

	/** Returns the incident title. */
	String getTitle();

	/** Returns the name of the incident's service. */
	String getServiceName();

	/** Returns the incident severity code. */
	String getSeverity();

	/** Returns the incident status code. */
	String getStatus();

	/** Returns the incident creation timestamp. */
	Instant getCreatedAt();

	/** Indicates whether the query matched the incident title. */
	Boolean getTitleMatch();

	/** Indicates whether the query matched the incident description. */
	Boolean getDescriptionMatch();

	/** Indicates whether the query matched any timeline entry. */
	Boolean getTimelineMatch();
}
