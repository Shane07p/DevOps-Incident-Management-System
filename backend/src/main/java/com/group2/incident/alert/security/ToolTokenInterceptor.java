package com.group2.incident.alert.security;

import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.service.AlertIntakeService;
import com.group2.incident.common.ratelimit.RateLimitResult;
import com.group2.incident.common.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
	private static final String RATE_LIMIT_BODY = "{\"status\":429,\"message\":\"Rate limit exceeded\",\"errors\":{}}";

	private final AlertIntakeService alertIntakeService;
	private final RateLimitService rateLimitService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		MonitoringTool tool = alertIntakeService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
		request.setAttribute(TOOL_ATTRIBUTE, tool);

		RateLimitResult result = rateLimitService.tryConsumeWebhook(tool.getId());
		if (!result.allowed()) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write(RATE_LIMIT_BODY);
			return false;
		}

		return true;
	}
}
