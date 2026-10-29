package com.group2.incident.alert.util;

import com.group2.incident.alert.dto.AlertRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class PayloadFingerprintUtil {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.build();

	private PayloadFingerprintUtil() {}

	public static String canonicalPayload(AlertRequest request, Long serviceId) {
		Map<String, Object> payload = new TreeMap<>();
		payload.put("correlationKey", request.correlationKey());
		payload.put("description", request.description());
		payload.put("details", request.details());
		payload.put("eventId", request.eventId());
		payload.put("severity", request.severity());
		payload.put("serviceId", serviceId);
		payload.put("source", request.source());
		payload.put("title", request.title());
		return OBJECT_MAPPER.writeValueAsString(payload);
	}

	public static String fingerprint(String canonicalPayload) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}
}
