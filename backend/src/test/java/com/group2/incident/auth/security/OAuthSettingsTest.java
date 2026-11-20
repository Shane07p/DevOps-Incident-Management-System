package com.group2.incident.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.group2.incident.auth.entity.OAuthProvider;
import org.junit.jupiter.api.Test;

/** Which providers are on, and where they send people back (D50). */
class OAuthSettingsTest {

	private static OAuthSettings.Client client(String id, String secret) {
		return new OAuthSettings.Client(id, secret);
	}

	@Test
	void aProviderIsOnOnlyWithBothItsIdAndSecret() {
		assertThat(new OAuthSettings("https://settle.test", client("id", "secret"), client("id", "secret"), null).enabled())
				.containsExactly(OAuthProvider.GOOGLE, OAuthProvider.GITHUB);
		assertThat(new OAuthSettings("https://settle.test", client("id", ""), client(" ", "secret"), null).enabled()).isEmpty();
		assertThat(new OAuthSettings("https://settle.test", client(null, "secret"), client("id", null), null).enabled()).isEmpty();
		assertThat(new OAuthSettings("https://settle.test", null, null, null).enabled()).isEmpty();
	}

	@Test
	void theCallbackIsOnThePublicUrlWithoutADoubleSlash() {
		OAuthSettings settings = new OAuthSettings(" https://settle.test/ ", null, null, null);

		assertThat(settings.publicBase()).isEqualTo("https://settle.test");
		assertThat(settings.callbackUri(OAuthProvider.GITHUB)).isEqualTo("https://settle.test/api/auth/oauth2/callback/github");
		assertThat(new OAuthSettings(null, null, null, null).publicBase()).isEmpty();
	}
}
