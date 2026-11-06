package com.group2.incident.incident.dto;

public record IncidentRolesDto(
		IncidentUserDto lead,
		IncidentUserDto comms,
		IncidentUserDto ops
) {
	public IncidentUserDto LEAD() {
		return lead;
	}

	public IncidentUserDto COMMS() {
		return comms;
	}

	public IncidentUserDto OPS() {
		return ops;
	}
}
