package com.group2.incident.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.service.OAuthSignInService;
import com.group2.incident.auth.service.ProviderAccount;
import com.group2.incident.support.IntegrationTest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Google and GitHub sign-in over HTTP, with both providers set up (D50). */
@IntegrationTest
@TestPropertySource(properties = {
		"app.oauth.public-url=https://settle.test/",
		"app.oauth.google.client-id=google-client",
		"app.oauth.google.client-secret=google-secret",
		"app.oauth.github.client-id=github-client",
		"app.oauth.github.client-secret=github-secret" })
class OAuthControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private OAuthSignInService signIn;

	private static String codeIn(String path) {
		return path.substring(path.indexOf("code=") + "code=".length());
	}

	private static String location(MvcResult result) {
		return URLDecoder.decode(result.getResponse().getHeader(HttpHeaders.LOCATION), StandardCharsets.UTF_8);
	}

	@Test
	void theProvidersThatAreSetUpAreListed() throws Exception {
		mvc.perform(get("/api/auth/oauth/providers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.providers[0]").value("google"))
				.andExpect(jsonPath("$.providers[1]").value("github"));
	}

	// TC-D50g: the provider sends people back to the public address, never to whatever host the request came in on.
	@Test
	void signingInWithGoogleGoesToGoogleWithTheRegisteredCallback() throws Exception {
		MvcResult result = mvc.perform(get("/api/auth/oauth2/authorization/google").header(HttpHeaders.HOST, "evil.test"))
				.andExpect(status().isFound())
				.andReturn();

		String location = location(result);
		assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
				.contains("client_id=google-client")
				.contains("redirect_uri=https://settle.test/api/auth/oauth2/callback/google")
				.contains("scope=openid profile email")
				.contains("state=");
	}

	@Test
	void signingInWithGitHubAsksForTheVerifiedEmails() throws Exception {
		String location = location(mvc.perform(get("/api/auth/oauth2/authorization/github"))
				.andExpect(status().isFound())
				.andReturn());

		assertThat(location).startsWith("https://github.com/login/oauth/authorize?")
				.contains("client_id=github-client")
				.contains("redirect_uri=https://settle.test/api/auth/oauth2/callback/github")
				.contains("scope=read:user user:email");
	}

	@Test
	void aProviderThatIsNotSetUpIsNotFound() throws Exception {
		mvc.perform(get("/api/auth/oauth2/authorization/gitlab")).andExpect(status().isNotFound());
	}

	// A callback with no sign-in behind it (a forged or replayed one) goes back to the sign-in page.
	@Test
	void aCallbackWithoutAStartedSignInIsRefused() throws Exception {
		MvcResult result = mvc.perform(get("/api/auth/oauth2/callback/google").param("code", "abc").param("state", "forged"))
				.andExpect(status().isFound())
				.andReturn();

		assertThat(location(result)).isEqualTo("https://settle.test/login?oauth=failed");
	}

	// TC-D50h
	@Test
	void aSignUpCodeShowsItsDetailsThenCreatesTheOrgAndALoginCodeWorksOnce() throws Exception {
		String signupCode = codeIn(signIn.finish(new ProviderAccount(OAuthProvider.GITHUB, "9001", "lee@http.test", true, "Lee")));

		mvc.perform(post("/api/auth/oauth/signup/details").contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + signupCode + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("lee@http.test"))
				.andExpect(jsonPath("$.provider").value("github"));
		mvc.perform(post("/api/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"" + signupCode + "\",\"orgName\":\"Lee's team\",\"displayName\":\"Lee Wong\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.org.name").value("Lee's team"))
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andExpect(jsonPath("$.token").isNotEmpty());

		String loginCode = codeIn(signIn.finish(new ProviderAccount(OAuthProvider.GITHUB, "9001", "lee@http.test", true, "Lee")));
		String body = "{\"code\":\"" + loginCode + "\"}";
		mvc.perform(post("/api/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.email").value("lee@http.test"));
		mvc.perform(post("/api/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("This sign-in link has expired or was already used. Sign in again."));
	}
}
