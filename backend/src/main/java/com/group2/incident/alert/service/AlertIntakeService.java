package com.group2.incident.alert.service;

import com.group2.incident.alert.dto.AlertAcceptedResponse;
import com.group2.incident.alert.dto.AlertRequest;
import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.common.error.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertIntakeService {

	private static final String BEARER_PREFIX = "Bearer ";

	private final ToolService toolService;
	private final AlertService alertService;

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
		return alertService.processAlert(tool, request);
	}
}
