package com.group2.incident.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * URL rules from the Sprint 1 permissions table. Until JWT login (D21) lands, requests
 * authenticate with HTTP Basic against app_user; the JWT work replaces {@code httpBasic} with
 * {@code oauth2ResourceServer} and keeps these rules.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// No server session and no cookie auth, so there is nothing for CSRF to exploit.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/error", "/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
								"/v3/api-docs/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/services", "/api/services/**").authenticated()
						.requestMatchers("/api/services", "/api/services/**").hasRole(Role.ADMIN.name())
						.anyRequest().authenticated())
				// A plain 401 without WWW-Authenticate, so browsers don't pop up a login dialog.
				.httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

	@Bean
	UserDetailsService userDetailsService(AppUserRepository users) {
		return username -> users.findByUsername(username)
				.map(user -> User.withUsername(user.getUsername())
						.password(user.getPasswordHash())
						.roles(user.getRole().name())
						.disabled(!user.isEnabled())
						.build())
				.orElseThrow(() -> new UsernameNotFoundException(username));
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
