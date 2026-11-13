package com.group2.incident.telemetry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

	private final TokenCipher cipher = new TokenCipher(new TelemetryProperties(KEY, false, 5));

	@Test
	void aTokenComesBackForItsOwnOrgAndIsNotStoredInTheClear() {
		String sealed = cipher.encrypt(7L, "glc_secret-token");

		assertThat(sealed).startsWith("v1:").doesNotContain("glc_secret-token");
		assertThat(cipher.decrypt(7L, sealed)).isEqualTo("glc_secret-token");
		// A new nonce each time: the same token never seals to the same text.
		assertThat(cipher.encrypt(7L, "glc_secret-token")).isNotEqualTo(sealed);
	}

	@Test
	void aCiphertextCopiedToAnotherOrgDoesNotDecrypt() {
		String sealed = cipher.encrypt(7L, "glc_secret-token");

		assertThatThrownBy(() -> cipher.decrypt(8L, sealed))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("The saved telemetry token can't be read; save it again");
	}

	@Test
	void aChangedKeyOrATamperedValueDoesNotDecryptAndSaysNothingAboutTheToken() {
		String sealed = cipher.encrypt(7L, "glc_secret-token");
		TokenCipher rotated = new TokenCipher(new TelemetryProperties(OTHER_KEY, false, 5));
		String tampered = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("AA") ? "BB" : "AA");

		assertThatThrownBy(() -> rotated.decrypt(7L, sealed)).isInstanceOf(ServiceUnavailableException.class)
				.message().doesNotContain("glc_secret-token");
		assertThatThrownBy(() -> cipher.decrypt(7L, tampered)).isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void withoutAKeyNothingIsSavedOrRead() {
		TokenCipher none = new TokenCipher(new TelemetryProperties("", false, 5));

		assertThatThrownBy(() -> none.encrypt(7L, "token")).isInstanceOf(ServiceUnavailableException.class)
				.hasMessage("Telemetry connections are not set up on this server");
		assertThatThrownBy(() -> none.decrypt(7L, "v1:AAAA")).isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void aKeyOfTheWrongSizeStopsTheServerAtStartup() {
		assertThatThrownBy(() -> new TokenCipher(new TelemetryProperties(
				Base64.getEncoder().encodeToString(new byte[16]), false, 5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 bytes");
	}
}
