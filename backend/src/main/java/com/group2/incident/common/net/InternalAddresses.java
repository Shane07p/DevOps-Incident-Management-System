package com.group2.incident.common.net;

import java.net.InetAddress;

/**
 * Addresses the backend must never call on a user's behalf: our own machine and networks. Monitors and
 * telemetry connections both send requests to addresses an Admin typed in, so both refuse these.
 */
public final class InternalAddresses {

	private InternalAddresses() {
	}

	/** True for any address that is not a public unicast one. */
	public static boolean isInternal(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		int first = bytes[0] & 0xff;
		if (bytes.length == 4) {
			int second = bytes[1] & 0xff;
			return first == 0 // "this network", 0.0.0.0/8
					|| (first == 100 && second >= 64 && second <= 127) // shared address space, 100.64.0.0/10
					|| (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) // IETF protocol assignments, 192.0.0.0/24
					|| (first == 198 && (second == 18 || second == 19)) // benchmarking, 198.18.0.0/15
					|| first >= 240; // reserved and broadcast
		}
		// IPv6 unique local addresses, fc00::/7; isSiteLocalAddress covers only the retired fec0::/10.
		return (first & 0xfe) == 0xfc;
	}
}
