package com.group2.incident.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP address from the HTTP request.
 * Untrusted forwarded headers (X-Forwarded-For) are ignored to prevent spoofing
 * and rate-limit bypass (D41, D42). Only trusted reverse proxies may supply
 * forwarded client addresses.
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

	private final RateLimitProperties properties;

	public String resolve(HttpServletRequest request) {
		return resolve(request, properties.trustedProxies());
	}

	public static String resolve(HttpServletRequest request, List<String> trustedProxies) {
		if (request == null) {
			return "127.0.0.1";
		}
		String remote = request.getRemoteAddr();
		String remoteAddr = (remote != null && !remote.isBlank()) ? remote.trim() : "127.0.0.1";

		if (trustedProxies == null || trustedProxies.isEmpty() || !isTrustedProxy(remoteAddr, trustedProxies)) {
			return remoteAddr;
		}

		String xForwardedFor = request.getHeader("X-Forwarded-For");
		if (xForwardedFor == null || xForwardedFor.isBlank()) {
			return remoteAddr;
		}

		String[] hops = xForwardedFor.split(",");
		// Traverse from right to left, stripping trusted intermediate proxies.
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (!hop.isEmpty() && !isTrustedProxy(hop, trustedProxies)) {
				return hop;
			}
		}

		// If all hops match trusted proxies, fall back to the leftmost hop.
		for (String hop : hops) {
			String trimmed = hop.trim();
			if (!trimmed.isEmpty()) {
				return trimmed;
			}
		}

		return remoteAddr;
	}

	static boolean isTrustedProxy(String ip, List<String> trustedProxies) {
		if (ip == null || ip.isBlank() || trustedProxies == null || trustedProxies.isEmpty()) {
			return false;
		}
		for (String pattern : trustedProxies) {
			if (matchesPattern(ip.trim(), pattern.trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesPattern(String ip, String pattern) {
		if (pattern.equalsIgnoreCase(ip)) {
			return true;
		}
		if (pattern.contains("/")) {
			return matchesCidr(ip, pattern);
		}
		try {
			InetAddress target = InetAddress.getByName(ip);
			InetAddress trusted = InetAddress.getByName(pattern);
			return target.equals(trusted);
		} catch (Exception ignored) {
			return false;
		}
	}

	private static boolean matchesCidr(String ip, String cidr) {
		int slash = cidr.indexOf('/');
		if (slash <= 0 || slash >= cidr.length() - 1) {
			return false;
		}
		String cidrIp = cidr.substring(0, slash).trim();
		int prefixLen;
		try {
			prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
		} catch (NumberFormatException e) {
			return false;
		}

		try {
			InetAddress targetAddr = InetAddress.getByName(ip);
			InetAddress cidrAddr = InetAddress.getByName(cidrIp);
			byte[] targetBytes = targetAddr.getAddress();
			byte[] cidrBytes = cidrAddr.getAddress();

			if (targetBytes.length != cidrBytes.length) {
				return false;
			}
			if (prefixLen < 0 || prefixLen > targetBytes.length * 8) {
				return false;
			}

			int fullBytes = prefixLen / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (targetBytes[i] != cidrBytes[i]) {
					return false;
				}
			}
			int remainingBits = prefixLen % 8;
			if (remainingBits > 0) {
				int mask = (0xFF << (8 - remainingBits)) & 0xFF;
				if ((targetBytes[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) {
					return false;
				}
			}
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}
}

