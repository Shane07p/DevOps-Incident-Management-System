package com.group2.incident.auth.security;

import com.group2.incident.auth.service.PersonalAccessTokenService;
import com.group2.incident.common.tenancy.OrgContextFilter;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT login. The token travels in the Authorization header, never in a cookie, so the browser
 * never sends it on its own and CSRF protection has nothing to protect.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/webhooks/**")
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtUserConverter jwtUserConverter,
			PersonalAccessTokenService personalAccessTokenService
	) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/error", "/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/signup").permitAll()
						// The public status page is for customers, who have no account (D45).
						.requestMatchers(HttpMethod.GET, "/api/public/status/*").permitAll()
						// Public heartbeats ping needs no login (FR-22).
						.requestMatchers("/api/public/heartbeats/**").permitAll()
						// The permissions table in the Sprint 1 plan; anything not listed needs any logged-in user.
						.requestMatchers("/api/users/**").hasRole("ADMIN")
						.requestMatchers("/api/tools/**").hasRole("ADMIN")
						.requestMatchers("/api/audit/**").hasRole("ADMIN")
						.requestMatchers("/api/settings/telemetry", "/api/settings/telemetry/**").hasRole("ADMIN")
						// Runbooks are written by Engineers too (FR-25), so their rule comes before the Admin-only one.
						.requestMatchers(HttpMethod.POST, "/api/services/*/runbooks").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.PUT, "/api/runbooks/*").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.DELETE, "/api/runbooks/*").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/services/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, "/api/services/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/monitors/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, "/api/monitors/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/heartbeats/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, "/api/heartbeats/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/incidents").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/acknowledge").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/transitions").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.PUT, "/api/incidents/*/severity").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.PUT, "/api/incidents/*/assignee").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.PUT, "/api/incidents/*/roles").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/comments").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/timeline/*/corrections")
						.hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/evidence")
						.hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/deploys/*")
						.hasAnyRole("ADMIN", "ENGINEER")
						// PublicUpdateService narrows Engineers to the incident's Lead and Comms (D45).
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/public-updates").hasAnyRole("ADMIN", "ENGINEER")
						// Reading follow-ups is open to every role; adding and changing them is for responders (#240).
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/follow-ups").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.PUT, "/api/follow-ups/*").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/investigations").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/incidents/*/drafts").hasAnyRole("ADMIN", "ENGINEER")
						.requestMatchers(HttpMethod.POST, "/api/investigations/*/actions/*/decision").hasAnyRole("ADMIN", "ENGINEER")
						.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.bearerTokenResolver(request -> {
							// A logged-in browser may send an expired token to the public page; it needs none.
							if (request.getRequestURI().startsWith("/api/public/")) {
								return null;
							}
							String header = request.getHeader(HttpHeaders.AUTHORIZATION);
							if (header != null && header.startsWith("Bearer ")) {
								String token = header.substring(7).trim();
								if (token.startsWith("pat_")) {
									return null;
								}
								return token;
							}
							return null;
						})
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtUserConverter)));
		// Intercept and authenticate personal access tokens before standard Bearer JWT filter.
		http.addFilterBefore(new PersonalAccessTokenFilter(personalAccessTokenService), BearerTokenAuthenticationFilter.class);
		// After the token is checked, the request works for the org in the token (D48).
		http.addFilterAfter(new OrgContextFilter(), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	/** JWT_SECRET: base64, at least 32 bytes (openssl rand -base64 32). */
	@Bean
	SecretKey jwtSigningKey(@Value("${app.jwt.secret}") String secret) {
		byte[] key;
		if (secret.isBlank()) {
			log.warn("JWT_SECRET is not set, so a random key is used and every token stops working on restart");
			key = new byte[32];
			new SecureRandom().nextBytes(key);
		}
		else {
			key = Base64.getDecoder().decode(secret.trim());
			if (key.length < 32) {
				throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
			}
		}
		return new SecretKeySpec(key, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
		// Expiry is checked against the same Clock that issued the token.
		JwtTimestampValidator expiry = new JwtTimestampValidator();
		expiry.setClock(clock);
		expiry.setAllowEmptyExpiryClaim(false);
		decoder.setJwtValidator(expiry);
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

}
