package com.group2.incident.alert.security;

import com.group2.incident.alert.service.AlertIntakeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks the tool token before the alert body is read, so a caller without a valid token gets 401
 * and learns nothing about the payload. The tool is handed to the controller as a request attribute.
 */
@Component
@RequiredArgsConstructor
public class ToolTokenInterceptor implements HandlerInterceptor {

	public static final String TOOL_ATTRIBUTE = "monitoringTool";

	private final AlertIntakeService alertIntakeService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		request.setAttribute(TOOL_ATTRIBUTE, alertIntakeService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION)));
		return true;
	}
}
