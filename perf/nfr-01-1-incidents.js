import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

/**
 * NFR-01.1 Performance Test: Incident Board & Incident Detail Endpoints
 *
 * Requirements:
 *   - Test with 1,000 stored incidents in database.
 *   - 10 concurrent Virtual Users (VUs).
 *   - Ramp up for 10 seconds, maintain load for 60 seconds, ramp down for 10 seconds.
 *   - 95th percentile response time must be under 2,000 ms for both endpoints.
 *   - HTTP failure rate must be below 1%.
 *
 * Environment variables:
 *   - BASE_URL: Backend URL (default: 'http://localhost:8080')
 *   - ADMIN_EMAIL: User login email (default: 'admin@settle.local')
 *   - ADMIN_PASSWORD: User login password (default: 'DevPassw0rd!')
 */

// Custom Trend metrics for fine-grained reporting
const boardDuration = new Trend('board_response_time', true);
const detailDuration = new Trend('detail_response_time', true);
const failureRate = new Rate('custom_failure_rate');

// Workload profile configuration:
// - Default (Local NFR-01.1 benchmark): 10 VUs for 80s (10s ramp, 60s steady, 10s ramp-down).
// - Deployed (WORKLOAD_PROFILE=deployed): Gentle 2 VUs for 30s (5s ramp, 20s steady, 5s ramp-down).
const isDeployed = __ENV.WORKLOAD_PROFILE === 'deployed';

export const options = {
  stages: isDeployed
    ? [
        { duration: '5s', target: 2 },  // Gentle ramp-up to 2 VUs
        { duration: '20s', target: 2 }, // Steady state with 2 VUs
        { duration: '5s', target: 0 },  // Gentle ramp-down
      ]
    : [
        { duration: '10s', target: 10 }, // Ramp-up to 10 VUs
        { duration: '60s', target: 10 }, // Steady state with 10 VUs
        { duration: '10s', target: 0 },  // Ramp-down to 0 VUs
      ],
  thresholds: {
    // Overall HTTP failure rate below 1%
    'http_req_failed': ['rate<0.01'],
    'custom_failure_rate': ['rate<0.01'],

    // NFR-01.1: 95th percentile response time under 2 seconds (2000 ms)
    'http_req_duration{endpoint:incidents_board}': ['p(95)<2000'],
    'http_req_duration{endpoint:incident_detail}': ['p(95)<2000'],
    'board_response_time': ['p(95)<2000'],
    'detail_response_time': ['p(95)<2000'],
  },
};

/**
 * Setup runs once before virtual users start:
 * 1. Authenticates against POST /api/auth/login to obtain a JWT bearer token.
 * 2. Fetches the current incident list via GET /api/incidents to obtain real incident IDs.
 * 3. Passes the token, available incident IDs, and base URL to all VUs.
 */
export function setup() {
  const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  const email = __ENV.ADMIN_EMAIL || 'admin@settle.local';
  const password = __ENV.ADMIN_PASSWORD || 'DevPassw0rd!';

  console.log(`[setup] Connecting to ${baseUrl} as ${email}`);

  // 1. Authenticate to obtain JWT token
  const loginPayload = JSON.stringify({ email, password });
  const loginRes = http.post(`${baseUrl}/api/auth/login`, loginPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  const loginOk = check(loginRes, {
    'login status is 200': (r) => r.status === 200,
    'login returned JWT token': (r) => {
      try {
        const body = r.json();
        return typeof body.token === 'string' && body.token.length > 0;
      } catch {
        return false;
      }
    },
  });

  if (!loginOk) {
    throw new Error(`[setup] Failed to log in as ${email}. Status: ${loginRes.status}. Body: ${loginRes.body}`);
  }

  const token = loginRes.json('token');

  // 2. Query initial incident list to harvest real incident IDs
  const listRes = http.get(`${baseUrl}/api/incidents`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Accept': 'application/json',
    },
  });

  const listOk = check(listRes, {
    'setup incident list status is 200': (r) => r.status === 200,
  });

  if (!listOk) {
    throw new Error(`[setup] Failed to query incident list. Status: ${listRes.status}. Body: ${listRes.body}`);
  }

  let incidentIds = [];
  try {
    const incidents = listRes.json();
    if (Array.isArray(incidents) && incidents.length > 0) {
      incidentIds = incidents.map((inc) => inc.id).filter((id) => typeof id === 'number');
    }
  } catch (err) {
    console.warn(`[setup] Could not parse incident list JSON: ${err}`);
  }

  console.log(`[setup] Discovered ${incidentIds.length} existing incident IDs in the database.`);
  if (incidentIds.length < 1000) {
    if (isDeployed) {
      console.log(`[setup] Deployed mode: Found ${incidentIds.length} incidents (non-destructive; 1,000 incident seed is not applied to deployed environment).`);
    } else {
      console.warn(`[setup] Warning: Only ${incidentIds.length} incidents found. NFR-01.1 specifies 1,000 incidents. Ensure seed_1000_incidents.sql was executed.`);
    }
  }

  // Fallback to default ID if list is empty
  if (incidentIds.length === 0) {
    incidentIds = [1];
  }

  return {
    token,
    baseUrl,
    incidentIds,
  };
}

/**
 * Main per-VU iteration loop:
 * Alternates between reading the incident board and inspecting a specific incident detail.
 */
export default function (data) {
  const authHeaders = {
    'Authorization': `Bearer ${data.token}`,
    'Accept': 'application/json',
  };

  // --------------------------------------------------------------------------
  // 1. GET /api/incidents (Incident Board)
  // --------------------------------------------------------------------------
  const boardRes = http.get(`${data.baseUrl}/api/incidents`, {
    headers: authHeaders,
    tags: { endpoint: 'incidents_board' },
  });

  const boardPassed = check(boardRes, {
    'board status is 200': (r) => r.status === 200,
    'board body is non-empty array': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body) && body.length > 0;
      } catch {
        return false;
      }
    },
    'board items contain required fields': (r) => {
      try {
        const first = r.json()[0];
        return typeof first.id === 'number' && typeof first.reference === 'string' && typeof first.status === 'string';
      } catch {
        return false;
      }
    },
  });

  boardDuration.add(boardRes.timings.duration);
  failureRate.add(!boardPassed);

  // --------------------------------------------------------------------------
  // 2. GET /api/incidents/{id} (Incident Detail)
  // --------------------------------------------------------------------------
  const randomId = data.incidentIds[Math.floor(Math.random() * data.incidentIds.length)];
  const detailRes = http.get(`${data.baseUrl}/api/incidents/${randomId}`, {
    headers: authHeaders,
    tags: { endpoint: 'incident_detail' },
  });

  const detailPassed = check(detailRes, {
    'detail status is 200': (r) => r.status === 200,
    'detail matches requested id': (r) => {
      try {
        return r.json('id') === randomId;
      } catch {
        return false;
      }
    },
    'detail contains timeline array': (r) => {
      try {
        return Array.isArray(r.json('timeline'));
      } catch {
        return false;
      }
    },
  });

  detailDuration.add(detailRes.timings.duration);
  failureRate.add(!detailPassed);

  // Small pacing pause between iterations (50ms)
  sleep(0.05);
}

