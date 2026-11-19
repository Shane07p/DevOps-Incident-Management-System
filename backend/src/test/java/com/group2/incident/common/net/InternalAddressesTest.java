package com.group2.incident.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guard that keeps monitors and telemetry connections off our own machine and networks: each reserved
 * range is checked at both of its edges and just outside them.
 */
class InternalAddressesTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"0.0.0.0", "0.1.2.3",                 // this network, 0.0.0.0/8
			"127.0.0.1", "::1",                   // loopback
			"10.0.0.1", "172.16.0.1", "192.168.1.1", // private
			"169.254.1.1", "fe80::1",             // link-local
			"224.0.0.1", "239.255.255.255", "ff02::1", // multicast
			"100.64.0.0", "100.127.255.255",      // shared address space, 100.64.0.0/10
			"192.0.0.0", "192.0.0.255",           // IETF protocol assignments, 192.0.0.0/24
			"198.18.0.0", "198.19.255.255",       // benchmarking, 198.18.0.0/15
			"240.0.0.1", "255.255.255.255",       // reserved and broadcast
			"fc00::1", "fdff:ffff::1",            // IPv6 unique local, fc00::/7
			"::"                                  // any local
	})
	void refusesOurOwnAndReservedAddresses(String literal) throws UnknownHostException {
		assertThat(InternalAddresses.isInternal(InetAddress.getByName(literal))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"8.8.8.8", "1.1.1.1",
			"100.63.255.255", "100.128.0.0",      // either side of 100.64.0.0/10
			"192.0.1.1", "192.1.0.0",             // next to 192.0.0.0/24
			"198.17.255.255", "198.20.0.0",       // either side of 198.18.0.0/15
			"223.255.255.255",                    // last address before multicast
			"2001:4860:4860::8888",
			"fb00::1", "fe00::1"                  // either side of fc00::/7, outside link-local
	})
	void allowsPublicAddresses(String literal) throws UnknownHostException {
		assertThat(InternalAddresses.isInternal(InetAddress.getByName(literal))).isFalse();
	}
}
