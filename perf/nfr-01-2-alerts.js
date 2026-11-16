import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

/**
 * NFR-01.2 Performance Test: Incoming Alert Ingestion Endpoint
 *
 * Requirements:
 *   - Target endpoint: POST /webhooks/alerts.
 *   - Constant arrival rate: exactly 10 alerts per second.
 *   - Duration: 30 seconds (300 total alerts).
 *   - Pre-allocated VUs: 10, Maximum VUs: 20.
 *   - 95th percentile response time must be under 1,000 ms.
 *   - HTTP failure rate must be below 1%.
 *
 * Authentication & Rate Limiting:
 *   - Authenticated via Monitoring Tool Token in Authorization header: "Bearer <TOOL_TOKEN>".
 *   - RateLimitProperties defaults to 50 webhook requests/second per tool token,
 *     which comfortably accommodates the 10 alerts/sec workload without hitting HTTP 429.
 *
 * Environment variables:
 *   - BASE_URL: Target backend URL (default: 'http://localhost:8080')
 *   - TOOL_TOKEN: Bearer token of registered monitoring tool (recommended).
 *   - SERVICE_NAME: Catalog service name (default: 'Payments')
 *   - ADMIN_EMAIL: Admin email to auto-provision tool if TOOL_TOKEN is unset (default: 'admin@settle.local')
 *   - ADMIN_PASSWORD: Admin password to auto-provision tool if TOOL_TOKEN is unset (default: 'DevPassw0rd!')
 */

const alertLatency = new Trend('alert_intake_latency', true);
const alertFailures = new Rate('alert_failure_rate');

export const options = {
  scenarios: {
    ten_alerts_per_second: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 20,
    },
  },
  thresholds: {
    // Overall failure rate must stay below 1%
    'http_req_failed': ['rate<0.01'],
    'alert_failure_rate': ['rate<0.01'],

    // NFR-01.2: 95th percentile response time must be under 1 second (1000 ms)
    'http_req_duration{endpoint:alert_intake}': ['p(95)<1000'],
    'alert_intake_latency': ['p(95)<1000'],
  },
};

/**
 * Setup runs once before test execution:
 * Resolves the TOOL_TOKEN. If not provided via environment variable,
 * attempts to register a temporary monitoring tool using admin credentials.
 */
export function setup() {
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  const serviceName = __ENV.SERVICE_NAME || 'Payments';
  let toolToken = __ENV.TOOL_TOKEN;
  let autoCreatedToolId = null;

  if (toolToken && toolToken.trim().length > 0) {
    console.log(`[setup] Using supplied TOOL_TOKEN against ${baseUrl}`);
    return { baseUrl, toolToken: toolToken.trim(), serviceName, autoCreatedToolId };
  }

  // Attempt auto-provisioning via admin login if TOOL_TOKEN is not explicitly provided
  const adminEmail = __ENV.ADMIN_EMAIL || 'admin@settle.local';
  const adminPassword = __ENV.ADMIN_PASSWORD || 'DevPassw0rd!';

  console.log(`[setup] No TOOL_TOKEN provided. Attempting to register test tool via ${adminEmail}...`);

  const loginRes = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ email: adminEmail, password: adminPassword }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (loginRes.status !== 200 || !loginRes.json('token')) {
    throw new Error(
      `[setup] TOOL_TOKEN is required. Auto-registration failed (status: ${loginRes.status}).\n` +
      `Provide TOOL_TOKEN via: -e TOOL_TOKEN=<token> or ensure admin credentials are correct.`
    );
  }

  const jwt = loginRes.json('token');
  const toolName = `k6-perf-tool-${Date.now()}`;

  const createToolRes = http.post(
    `${baseUrl}/api/tools`,
    JSON.stringify({ name: toolName }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${jwt}`,
      },
    }
  );

  if (createToolRes.status !== 201 || !createToolRes.json('token')) {
    throw new Error(
      `[setup] Failed to register temporary monitoring tool. Status: ${createToolRes.status}. Body: ${createToolRes.body}`
    );
  }

  toolToken = createToolRes.json('token');
  autoCreatedToolId = createToolRes.json('id');
  console.log(`[setup] Successfully registered temporary tool '${toolName}' (ID: ${autoCreatedToolId}).`);

  return {
    baseUrl,
    toolToken,
    serviceName,
    autoCreatedToolId,
    adminJwt: jwt,
  };
}

/**
 * Main per-iteration function invoked at 10 iterations per second:
 * Submits a synthetic alert conforming to AlertRequest DTO.
 */
export default function (data) {
  const eventId = `k6-evt-${__VU}-${__ITER}-${Date.now()}`;
  const correlationKey = `k6-corr-${__VU % 4}`;

  const payload = JSON.stringify({
    source: 'k6-load-generator',
    eventId: eventId,
    service: data.serviceName,
    title: `Synthetic High Latency Alert (${eventId})`,
    description: 'Triggered during k6 constant-arrival-rate performance check for NFR-01.2.',
    severity: 'SEV2',
    correlationKey: correlationKey,
    details: {
      generator: 'k6',
      iteration: __ITER,
      vu: __VU,
    },
  });

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.toolToken}`,
  };

  const res = http.post(`${data.baseUrl}/webhooks/alerts`, payload, {
    headers: headers,
    tags: { endpoint: 'alert_intake' },
  });

  const passed = check(res, {
    'alert intake status is 202': (r) => r.status === 202,
    'status field is ACCEPTED': (r) => {
      try {
        return r.json('status') === 'ACCEPTED';
      } catch {
        return false;
      }
    },
    'eventId matches sent payload': (r) => {
      try {
        return r.json('eventId') === eventId;
      } catch {
        return false;
      }
    },
  });

  alertLatency.add(res.timings.duration);
  alertFailures.add(!passed);
}

/**
 * Teardown runs once after all iterations complete:
 * Cleans up temporary monitoring tool if one was auto-registered in setup.
 */
export function teardown(data) {
  if (data.autoCreatedToolId && data.adminJwt) {
    console.log(`[teardown] Revoking temporary tool ID ${data.autoCreatedToolId}...`);
    http.del(`${data.baseUrl}/api/tools/${data.autoCreatedToolId}`, null, {
      headers: {
        'Authorization': `Bearer ${data.adminJwt}`,
      },
    });
  }
}

