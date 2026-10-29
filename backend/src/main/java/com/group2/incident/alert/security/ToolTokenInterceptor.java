package com.group2.incident.alert.security;

import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.service.AlertIntakeService;
import com.group2.incident.common.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks the tool token before the alert body is read, so a caller without a valid token gets 401
 * and learns nothing about the payload. The tool is handed to the controller as a request attribute.
 * Authenticated tools are then rate-limited per tool token (NFR-04.4, D42).
 */
@Component
@RequiredArgsConstructor
public class ToolTokenInterceptor implements HandlerInterceptor {

	public static final String TOOL_ATTRIBUTE = "monitoringTool";

	private final AlertIntakeService alertIntakeService;
	private final RateLimitService rateLimitService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		MonitoringTool tool = alertIntakeService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
		rateLimitService.checkWebhook(tool.getId());
		request.setAttribute(TOOL_ATTRIBUTE, tool);
		return true;
	}
}
