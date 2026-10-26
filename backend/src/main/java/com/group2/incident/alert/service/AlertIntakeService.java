package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.common.error.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertIntakeService {

	private static final String BEARER_PREFIX = "Bearer ";

	private final ToolService toolService;

	/** Called by ToolTokenInterceptor before the body is read. */
	public MonitoringTool authenticate(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
			throw new UnauthorizedException("Missing or malformed Authorization header");
		}

		String rawToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
		if (rawToken.isEmpty()) {
			throw new UnauthorizedException("Missing or malformed Authorization header");
		}

		return toolService.validateToken(rawToken);
	}

	public AlertAcceptedResponse processAlert(MonitoringTool tool, AlertRequest request) {
		log.info("Accepted alert '{}' (eventId: {}) for service '{}' from tool '{}' (toolId: {})",
				request.title(), request.eventId(), request.service(), tool.getName(), tool.getId());

		return AlertAcceptedResponse.of(request.eventId(), request.source());
	}
}
