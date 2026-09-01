// Milestone 8 (brief section 20): "Gateway memory stays flat while serving
// concurrent range requests" and "revocation latency distributions are
// measured under concurrent load."
//
// Run:
//   BASE_URL=http://localhost:8080 k6 run tests/k6/media-gateway-load.js
//
// This hammers the media gateway's range-request path against a real
// published video's HLS segment. Watch backend/app's JVM heap in Grafana (or
// `curl localhost:8080/actuator/prometheus | grep jvm_memory_used_bytes`)
// before/during/after the run — Rule 19 (never buffer a whole object to
// serve part of it) means heap should stay flat regardless of concurrency,
// not grow with the request rate.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin-m5-verify@example.com';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Password123!';
const VIDEO_ID = __ENV.VIDEO_ID || '07eaf3a8-6f43-4859-87fe-360323f319c9';
const SEGMENT_PATH = `/media/videos/${VIDEO_ID}/1/720p/segment_000.ts`;
const SEGMENT_BYTES = 227328; // 222KiB, from a live `mc ls` of this fixture segment

export const options = {
  scenarios: {
    concurrent_range_requests: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Local target (brief section 20: "defined local load targets are met").
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  const login = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (login.status !== 200) {
    throw new Error(`setup login failed: ${login.status} ${login.body}`);
  }
  const sessionCookie = login.cookies['sv_session'][0].value;

  const session = http.post(
    `${BASE_URL}/api/v1/videos/${VIDEO_ID}/public-playback-session`,
    null,
    { headers: { Cookie: `sv_session=${sessionCookie}` } },
  );
  if (session.status !== 200) {
    throw new Error(`setup playback session failed: ${session.status} ${session.body}`);
  }
  const playbackCookie = session.cookies['sv_playback'][0].value;

  return { cookieHeader: `sv_session=${sessionCookie}; sv_playback=${playbackCookie}` };
}

export default function (data) {
  // A real HLS player fetches segments in bounded chunks via Range, not the
  // whole file at once -- exercise the same path Rule 19 protects.
  const start = Math.floor(Math.random() * (SEGMENT_BYTES - 65536));
  const end = start + 65535;
  const res = http.get(`${BASE_URL}${SEGMENT_PATH}`, {
    headers: { Cookie: data.cookieHeader, Range: `bytes=${start}-${end}` },
  });
  check(res, {
    'range request succeeded (206 or 200)': (r) => r.status === 206 || r.status === 200,
  });
  sleep(0.1);
}
