package com.settle.telemetry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.settle.common.error.ServiceUnavailableException;
import com.settle.telemetry.config.TelemetryProperties;
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

	@Test
	void aValueThatWasNeverSealedReadsAsNotSetUp() {
		assertThatThrownBy(() -> cipher.decrypt(1L, null)).isInstanceOf(ServiceUnavailableException.class);
		assertThatThrownBy(() -> cipher.decrypt(1L, "glc_plain_token")).isInstanceOf(ServiceUnavailableException.class);
	}

	@Test
	void aKeyThatIsNotBase64StopsTheServerAtStartup() {
		assertThatThrownBy(() -> new TokenCipher(new TelemetryProperties("not base64!", false, 5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must be base64");
	}

	// No key set: Grafana can't be connected, and nothing pretends otherwise.
	@Test
	void aMissingOrBlankKeyMeansTelemetryIsNotSetUp() {
		TelemetryProperties unset = new TelemetryProperties(null, false, 0);

		assertThat(unset.secretKey()).isEmpty();
		assertThat(unset.timeoutSeconds()).isEqualTo(5);
		assertThatThrownBy(() -> new TokenCipher(unset).encrypt(1L, "glc_token")).isInstanceOf(ServiceUnavailableException.class);
		assertThatThrownBy(() -> new TokenCipher(new TelemetryProperties("  ", false, 5)).decrypt(1L, "x"))
				.isInstanceOf(ServiceUnavailableException.class);
	}
}
