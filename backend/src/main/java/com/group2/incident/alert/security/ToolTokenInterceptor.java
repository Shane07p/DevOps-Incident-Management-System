package com.group2.incident.alert.security;

import com.group2.incident.alert.entity.MonitoringTool;
import com.group2.incident.alert.service.AlertIntakeService;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.common.tenancy.OrgContext;
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

	private static final String PREVIOUS_ORG = ToolTokenInterceptor.class.getName() + ".previousOrg";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// The token is checked before any org is known, so this one lookup sees every org (D48). Token
		// hashes are unique across orgs.
		MonitoringTool tool = OrgContext.runAsRoot(() -> alertIntakeService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION)));
		rateLimitService.checkWebhook(tool.getId());
		// The alert, and any incident it opens, belong to the tool's org.
		request.setAttribute(PREVIOUS_ORG, OrgContext.current());
		OrgContext.set(tool.getOrgId());
		request.setAttribute(TOOL_ATTRIBUTE, tool);
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (request.getAttribute(PREVIOUS_ORG) instanceof Long previous) {
			OrgContext.set(previous);
		}
	}
}
