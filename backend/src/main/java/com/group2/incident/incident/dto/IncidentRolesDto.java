package com.group2.incident.incident.dto;

/** Who holds Comms and Ops. The Lead is the incident's assignee (D39), so it is not repeated here. */
public record IncidentRolesDto(
		IncidentUserDto comms,
		IncidentUserDto ops
) {
}
