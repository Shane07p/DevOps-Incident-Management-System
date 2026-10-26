package com.group2.incident.alert.dto;

public record AlertAcceptedResponse(
		String status,
		String eventId,
		String source,
		String message
) {
	public static AlertAcceptedResponse of(String eventId, String source) {
		return new AlertAcceptedResponse("ACCEPTED", eventId, source, "Alert accepted for processing");
	}
}
