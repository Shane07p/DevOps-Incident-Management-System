package com.settle.auth.security;

import com.settle.auth.entity.OAuthProvider;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The Google and GitHub registrations (D50), only for the providers whose id and secret are set. Spring Boot's own
 * registration properties would refuse to start without them; this keeps both providers optional.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthSettings.class)
class OAuthClientConfig {

	@Bean
	ClientRegistrationRepository clientRegistrationRepository(OAuthSettings settings) {
		Map<String, ClientRegistration> registrations = new HashMap<>();
		for (OAuthProvider provider : settings.enabled()) {
			OAuthSettings.Client client = settings.client(provider);
			ClientRegistration.Builder builder = switch (provider) {
				// openid, profile, email: Google says whether the email is verified.
				case GOOGLE -> CommonOAuth2Provider.GOOGLE.getBuilder(provider.registrationId());
				// user:email reads the verified addresses, which /user alone may not show.
				case GITHUB -> CommonOAuth2Provider.GITHUB.getBuilder(provider.registrationId()).scope("read:user", "user:email");
			};
			registrations.put(provider.registrationId(), builder
					.clientId(client.clientId().trim())
					.clientSecret(client.clientSecret().trim())
					.redirectUri(settings.callbackUri(provider))
					.build());
		}
		return registrations::get;
	}
}
