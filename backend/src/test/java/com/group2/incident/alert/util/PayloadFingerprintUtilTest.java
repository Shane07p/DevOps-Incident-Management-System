package com.group2.incident.alert.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.alert.dto.AlertRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadFingerprintUtilTest {

	@Test
	void canonicalFingerprintIgnoresMapPropertyOrder() {
		Map<String, Object> firstDetails = new LinkedHashMap<>();
		firstDetails.put("z", 2);
		firstDetails.put("a", 1);
		Map<String, Object> reorderedDetails = new LinkedHashMap<>();
		reorderedDetails.put("a", 1);
		reorderedDetails.put("z", 2);

		AlertRequest first = alert(firstDetails, "payments-key");
		AlertRequest reordered = alert(reorderedDetails, "payments-key");

		assertThat(fingerprint(first, 42L)).isEqualTo(fingerprint(reordered, 42L));
	}

	@Test
	void canonicalFingerprintIncludesServiceIdAndCorrelationKey() {
		AlertRequest request = alert(Map.of("metric", "latency"), "payments-key");

		assertThat(fingerprint(request, 42L)).isNotEqualTo(fingerprint(request, 43L));
		assertThat(fingerprint(request, 42L)).isNotEqualTo(fingerprint(alert(request.details(), "other-key"), 42L));
	}

	private static String fingerprint(AlertRequest request, Long serviceId) {
		return PayloadFingerprintUtil.fingerprint(PayloadFingerprintUtil.canonicalPayload(request, serviceId));
	}

	private static AlertRequest alert(Map<String, Object> details, String correlationKey) {
		return new AlertRequest(
				"prometheus",
				"evt-1",
				"Payments",
				"High latency",
				"P99 latency exceeded",
				"SEV2",
				correlationKey,
				details
		);
	}
}
