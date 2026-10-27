package com.group2.incident.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

	@Test
	void nullRequestReturnsLoopback() {
		assertThat(ClientIpResolver.resolve(null, List.of())).isEqualTo("127.0.0.1");
	}

	@Test
	void untrustedDirectPeerReturnsRemoteAddrAndIgnoresForwardedFor() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.10");
		request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");

		// No trusted proxies configured (default)
		String ip = ClientIpResolver.resolve(request, List.of());
		assertThat(ip).isEqualTo("203.0.113.10");
	}

	@Test
	void trustedProxyResolvesSingleForwardedForIp() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");
		request.addHeader("X-Forwarded-For", "198.51.100.5");

		String ip = ClientIpResolver.resolve(request, List.of("10.0.0.1"));
		assertThat(ip).isEqualTo("198.51.100.5");
	}

	@Test
	void trustedProxyStripsIntermediateTrustedProxiesInChain() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.2");
		request.addHeader("X-Forwarded-For", "198.51.100.5, 10.0.0.1");

		String ip = ClientIpResolver.resolve(request, List.of("10.0.0.1", "10.0.0.2"));
		assertThat(ip).isEqualTo("198.51.100.5");
	}

	@Test
	void attackerSpoofingThroughTrustedProxyIsThwarted() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");
		// Attacker passed 1.1.1.1, proxy appended real attacker IP 198.51.100.5
		request.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.5");

		String ip = ClientIpResolver.resolve(request, List.of("10.0.0.1"));
		// Resolver stops at the first untrusted IP from the right (198.51.100.5)
		assertThat(ip).isEqualTo("198.51.100.5");
	}

	@Test
	void trustedProxyCidrNotationMatching() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("172.18.0.5");
		request.addHeader("X-Forwarded-For", "198.51.100.20");

		String ip = ClientIpResolver.resolve(request, List.of("172.16.0.0/12"));
		assertThat(ip).isEqualTo("198.51.100.20");
	}

	@Test
	void trustedProxyWithoutForwardedHeaderFallsBackToRemoteAddr() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");

		String ip = ClientIpResolver.resolve(request, List.of("10.0.0.1"));
		assertThat(ip).isEqualTo("10.0.0.1");
	}
}
