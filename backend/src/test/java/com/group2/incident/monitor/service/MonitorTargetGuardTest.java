package com.group2.incident.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.common.error.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Monitors may only point at the outside world: no server-side request forgery. IP literals, so no DNS is needed. */
class MonitorTargetGuardTest {

	private final MonitorTargetGuard guard = new MonitorTargetGuard(false);

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"http://127.0.0.1/health",
			"http://localhost:8080/health",
			"http://10.0.0.5/",
			"http://172.16.3.4/",
			"http://192.168.1.1/",
			"http://169.254.169.254/latest/meta-data/",
			"http://100.64.0.1/",
			"http://0.0.0.0/",
			"http://224.0.0.1/",
			"http://[::1]/",
			"http://[fd00::1]/",
			"http://[fe80::1]/",
			"http://[::ffff:127.0.0.1]/"
	})
	@DisplayName("TC-FR-22.1f: Private, local, and metadata addresses are refused")
	void refusesInternalAddresses(String url) {
		assertThatThrownBy(() -> guard.check(url))
				.isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("url"))
				.hasMessageContaining("private or local network address");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "ftp://203.0.113.10/file", "javascript:alert(1)", "not a url", "https://", "http://user:secret@203.0.113.10/" })
	@DisplayName("Only plain http and https URLs with a host are accepted")
	void refusesOtherUrls(String url) {
		assertThatThrownBy(() -> guard.check(url))
				.isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("url"));
	}

	@Test
	@DisplayName("A public address is accepted")
	void acceptsPublicAddresses() {
		assertThat(guard.check("https://203.0.113.10/health").getHost()).isEqualTo("203.0.113.10");
		assertThat(guard.check(" http://8.8.8.8:8080/status ").getPort()).isEqualTo(8080);
	}

	@Test
	@DisplayName("A local run may allow private targets")
	void privateTargetsCanBeAllowedForLocalRuns() {
		assertThat(new MonitorTargetGuard(true).check("http://localhost:8080/health").getHost()).isEqualTo("localhost");
	}
}
