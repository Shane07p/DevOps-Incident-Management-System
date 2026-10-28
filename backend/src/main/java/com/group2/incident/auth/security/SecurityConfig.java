package com.group2.incident.auth.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUserConverter jwtUserConverter) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/error", "/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						// The permissions table in the Sprint 1 plan; anything not listed needs any logged-in user.
						.requestMatchers("/api/users/**").hasRole("ADMIN")
						.requestMatchers("/api/tools/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/services/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.PUT, "/api/services/**").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/api/incidents").hasAnyRole("ADMIN", "ENGINEER")
						.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtUserConverter)));
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
