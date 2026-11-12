package com.group2.incident.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Cache;
import com.group2.incident.common.ratelimit.RateLimitService;
import com.group2.incident.support.IntegrationTest;
import com.group2.incident.support.TestOrgs;
import com.group2.incident.support.TestOrgs.SignedUp;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each org connects its own Grafana and reads only from it (D49, #260). Two orgs each have a service named
 * Payments and their own stub Grafana; neither ever reaches the other's. Outside the test transaction, so each
 * request opens its session in its caller's org, as in production. The stubs listen on 127.0.0.1, so this
 * class allows private targets; {@code TelemetryHttpTest} covers the address guard with them refused.
 */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryConnectionTest {

	private static final String TOKEN_A = "glc_token-of-org-a";

	private static final String TOKEN_B = "glc_token-of-org-b";

	@DynamicPropertySource
	static void telemetry(DynamicPropertyRegistry registry) {
		// The key comes from @IntegrationTest; only the stubs on 127.0.0.1 need private targets allowed.
		registry.add("app.telemetry.allow-private-targets", () -> "true");
	}

	@Autowired
	private TestOrgs orgs;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RateLimitService rateLimitService;

	private SignedUp a;

	private SignedUp b;

	private long paymentsOfA;

	private long paymentsOfB;

	private final Stub grafanaOfA = new Stub();

	private final Stub grafanaOfB = new Stub();

	@BeforeAll
	void createTwoOrgsWithAServiceOfTheSameName() throws Exception {
		((Cache<?, ?>) ReflectionTestUtils.getField(rateLimitService, "loginBuckets")).invalidateAll();
		a = orgs.signUp("telemetry-a@a.test", "Telemetry A");
		b = orgs.signUp("telemetry-b@b.test", "Telemetry B");
		paymentsOfA = orgs.createService(a.adminToken(), "Payments", a.adminId());
		paymentsOfB = orgs.createService(b.adminToken(), "Payments", b.adminId());
		grafanaOfA.start();
		grafanaOfB.start();
	}

	@AfterAll
	void cleanUp() {
		grafanaOfA.stop();
		grafanaOfB.stop();
		if (a != null) {
			orgs.delete(a.orgId());
		}
		if (b != null) {
			orgs.delete(b.orgId());
		}
	}

	@BeforeEach
	void connectBoth() throws Exception {
		connect(a, grafanaOfA, TOKEN_A);
		connect(b, grafanaOfB, TOKEN_B);
		grafanaOfA.requests.clear();
		grafanaOfB.requests.clear();
	}

	@Test
	void eachOrgReadsLogsAndMetricsOnlyFromItsOwnGrafana() throws Exception {
		send(a, get("/api/telemetry/logs").queryParam("serviceId", String.valueOf(paymentsOfA)), null)
				.andExpect(status().isOk());
		send(a, get("/api/telemetry/metrics").queryParam("serviceId", String.valueOf(paymentsOfA))
				.queryParam("metric", "requestRate"), null)
				.andExpect(status().isOk());

		assertThat(grafanaOfB.requests).isEmpty();
		assertThat(grafanaOfA.requests).hasSize(2);
		assertThat(grafanaOfA.requests).allSatisfy(request -> assertThat(request.authorization())
				.isEqualTo(basic("a-user", TOKEN_A)));
		assertThat(grafanaOfA.requests.get(0).query()).contains("{service=\"payments\"}");

		send(b, get("/api/telemetry/logs").queryParam("serviceId", String.valueOf(paymentsOfB)), null)
				.andExpect(status().isOk());

		assertThat(grafanaOfA.requests).hasSize(2);
		assertThat(grafanaOfB.requests).singleElement()
				.satisfies(request -> assertThat(request.authorization()).isEqualTo(basic("b-user", TOKEN_B)));
	}

	@Test
	void withoutItsOwnConnectionAnOrgGets503AndNeverAnotherOrgsData() throws Exception {
		send(a, delete("/api/settings/telemetry"), null).andExpect(status().isNoContent());

		send(a, get("/api/telemetry/logs").queryParam("serviceId", String.valueOf(paymentsOfA)), null)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Telemetry isn't connected for your organization"));

		assertThat(grafanaOfA.requests).isEmpty();
		assertThat(grafanaOfB.requests).isEmpty();
	}

	@Test
	void theTokenIsNeverReturnedStoredInTheClearOrAudited() throws Exception {
		String saved = send(a, get("/api/settings/telemetry"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.connected").value(true))
				.andExpect(jsonPath("$.tokenSaved").value(true))
				.andExpect(jsonPath("$.lokiUrl").value(grafanaOfA.url()))
				.andReturn().getResponse().getContentAsString();

		assertThat(saved).doesNotContain(TOKEN_A);
		assertThat(jdbc.queryForList("SELECT token_ciphertext FROM telemetry_connection WHERE org_id = ?", String.class,
				a.orgId())).singleElement().satisfies(stored -> assertThat(stored).startsWith("v1:").doesNotContain(TOKEN_A));
		assertThat(jdbc.queryForList("SELECT details FROM audit_entry WHERE org_id = ? AND action = 'TELEMETRY_CONNECTED'",
				String.class, a.orgId())).isNotEmpty().allSatisfy(details -> assertThat(details).doesNotContain(TOKEN_A));
	}

	@Test
	void theFirstSaveNeedsATokenAndALaterOneKeepsIt() throws Exception {
		send(a, delete("/api/settings/telemetry"), null).andExpect(status().isNoContent());
		send(a, put("/api/settings/telemetry"), body(grafanaOfA, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.token").exists());

		connect(a, grafanaOfA, TOKEN_A);
		send(a, put("/api/settings/telemetry"), body(grafanaOfA, ""))
				.andExpect(status().isOk());
		send(a, get("/api/telemetry/logs").queryParam("serviceId", String.valueOf(paymentsOfA)), null)
				.andExpect(status().isOk());

		assertThat(grafanaOfA.requests).last()
				.satisfies(request -> assertThat(request.authorization()).isEqualTo(basic("a-user", TOKEN_A)));
	}

	@Test
	void aServiceCanSayHowItIsLabelledAndTheOrgItsMetricNames() throws Exception {
		send(a, put("/api/settings/telemetry"), """
				{"lokiUrl": "%s", "prometheusUrl": "%s/api/prom", "serviceLabel": "app", "requestsMetric": "http_server_requests_total",
				 "statusLabel": "code"}""".formatted(grafanaOfA.url(), grafanaOfA.url()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.serviceLabel").value("app"))
				.andExpect(jsonPath("$.durationMetric").value("http_request_duration_seconds"));
		// Its own service, so the label set here can't change what the other tests query.
		long billing = orgs.createService(a.adminToken(), "Billing", a.adminId());
		String target = "{\"responseMinutes\": 15, \"resolutionMinutes\": 240}";
		send(a, put("/api/services/{id}", billing), """
				{"name": "Billing", "ownerId": %d, "criticality": "HIGH", "telemetryLabel": "billing-api",
				 "targets": {"SEV1": %s, "SEV2": %s, "SEV3": %s, "SEV4": %s}}"""
				.formatted(a.adminId(), target, target, target, target))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.telemetryLabel").value("billing-api"));

		send(a, get("/api/telemetry/metrics").queryParam("serviceId", String.valueOf(billing))
				.queryParam("metric", "errorRate"), null)
				.andExpect(status().isOk());

		assertThat(grafanaOfA.requests).last().satisfies(request -> assertThat(request.query()).contains(
				"http_server_requests_total{app=\"billing-api\",code=~\"5..\"}"));
	}

	@Test
	void namesThatAreNotPrometheusNamesAreRefused() throws Exception {
		send(a, put("/api/settings/telemetry"), """
				{"lokiUrl": "%s", "serviceLabel": "service\\"} or {x=\\"1"}""".formatted(grafanaOfA.url()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.serviceLabel").exists());
		send(a, put("/api/settings/telemetry"), "{\"lokiUrl\": \"\", \"prometheusUrl\": \"\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.prometheusUrl").exists());
	}

	@Test
	void testingTheConnectionTriesEachStore() throws Exception {
		send(a, post("/api/settings/telemetry/test"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prometheus").value("ok"))
				.andExpect(jsonPath("$.loki").value("ok"));

		assertThat(grafanaOfA.requests).extracting(Stub.Request::path)
				.containsExactlyInAnyOrder("/api/prom/api/v1/query", "/loki/api/v1/labels");
		assertThat(grafanaOfB.requests).isEmpty();
	}

	/** Either store may be left out; Test reports the missing one and still tries the other (Codex on #261). */
	@Test
	void aLogsOnlyConnectionIsTestedAndSaysMetricsAreNotConnected() throws Exception {
		send(a, put("/api/settings/telemetry"), """
				{"lokiUrl": "%s", "lokiUser": "a-user", "prometheusUrl": ""}""".formatted(grafanaOfA.url()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prometheusUrl").doesNotExist());

		send(a, post("/api/settings/telemetry/test"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.prometheus").value("not connected"))
				.andExpect(jsonPath("$.loki").value("ok"));
		send(a, get("/api/telemetry/metrics").queryParam("serviceId", String.valueOf(paymentsOfA))
				.queryParam("metric", "requestRate"), null)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("Metrics aren't connected for your organization"));

		assertThat(grafanaOfA.requests).extracting(Stub.Request::path).containsExactly("/loki/api/v1/labels");
	}

	// ---------- helpers ----------

	private void connect(SignedUp org, Stub grafana, String token) throws Exception {
		send(org, put("/api/settings/telemetry"), body(grafana, token)).andExpect(status().isOk());
	}

	private static String body(Stub grafana, String token) {
		String user = grafana.user;
		return """
				{"prometheusUrl": "%s/api/prom", "prometheusUser": "%s", "lokiUrl": "%s", "lokiUser": "%s"%s}"""
				.formatted(grafana.url(), user, grafana.url(), user, token == null ? "" : ", \"token\": \"" + token + "\"");
	}

	private org.springframework.test.web.servlet.ResultActions send(SignedUp as, MockHttpServletRequestBuilder request,
			String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + as.adminToken());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private static String basic(String user, String token) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
	}

	/** A stand-in Grafana that answers Loki and Prometheus with empty results and records each request. */
	private final class Stub {

		record Request(String path, String query, String authorization) {
		}

		private final List<Request> requests = new CopyOnWriteArrayList<>();

		private HttpServer server;

		private String user;

		void start() {
			user = this == grafanaOfA ? "a-user" : "b-user";
			try {
				server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}
			server.createContext("/", exchange -> {
				String raw = exchange.getRequestURI().getRawQuery();
				requests.add(new Request(exchange.getRequestURI().getPath(),
						raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8),
						exchange.getRequestHeaders().getFirst("Authorization")));
				byte[] body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		void stop() {
			if (server != null) {
				server.stop(0);
			}
		}
	}
}
