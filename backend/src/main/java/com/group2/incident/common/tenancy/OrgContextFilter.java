package com.group2.incident.common.tenancy;

import com.group2.incident.auth.security.JwtUserConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after the token is checked: the request works for the org in the token, then the old value returns. */
public class OrgContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token)
				|| !(token.getToken().getClaim(JwtUserConverter.ORG) instanceof Number org)) {
			chain.doFilter(request, response);
			return;
		}
		long previous = OrgContext.current();
		OrgContext.set(org.longValue());
		try {
			chain.doFilter(request, response);
		}
		finally {
			OrgContext.set(previous);
		}
	}

}
