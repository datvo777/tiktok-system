// Sizes the cost of the status polling the web client does while a video is
// processing (web/src/Upload.tsx -> GET /api/v1/videos/{id}), independently of
// the transcode itself. upload-poll-load.js runs a real transcode per VU, so it
// cannot reach the waiter counts where polling cost starts to matter.
//
// Each VU is one browser waiting on one video: it polls its own video at
// POLL_MS. Setup creates ACCOUNTS accounts with one draft video each, and VUs
// are spread across them, so the reads hit different rows and owners.
//
// Run:
//   BASE_URL=http://localhost:8080 VUS=500 POLL_MS=2000 DURATION=60s \
//     k6 run tests/k6/video-status-poll-load.js
//
// The backend must have registration rate limiting off
// (SHORTVIDEO_REGISTER_RATE_LIMIT_ENABLED=false), or setup trips the per-IP cap.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const POLL_MS = Number(__ENV.POLL_MS || 2000);
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 50);

export const options = {
  scenarios: {
    waiters: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250', 'p(99)<1000'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };
const throttled = new Counter('polls_throttled');

export function setup() {
  const targets = [];
  const run = Date.now();
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `k6-poll-${run}-${i}@example.com`;
    const password = 'Password123!';
    http.post(`${BASE_URL}/api/v1/accounts`, JSON.stringify({ email, password, displayName: `k6 poll ${i}` }), json);
    const login = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), json);
    const cookie = `sv_session=${login.cookies['sv_session'][0].value}`;
    const upload = http.post(
      `${BASE_URL}/api/v1/uploads`,
      JSON.stringify({ title: 'k6 poll', sizeBytes: 1024 * 1024 }),
      { headers: { Cookie: cookie, 'Content-Type': 'application/json' } },
    );
    if (upload.status !== 200 && upload.status !== 201) {
      throw new Error(`setup: create upload failed for ${email}: ${upload.status} ${upload.body}`);
    }
    targets.push({ cookie, videoId: upload.json('videoId') });
  }
  return targets;
}

export default function (targets) {
  const t = targets[(__VU - 1) % targets.length];
  // Start at a random point in the interval so VUs do not poll in lockstep.
  if (__ITER === 0) sleep((Math.random() * POLL_MS) / 1000);
  const res = http.get(`${BASE_URL}/api/v1/videos/${t.videoId}`, { headers: { Cookie: t.cookie } });
  if (res.status === 429 || res.status === 503) throttled.add(1);
  check(res, { 'poll ok': (r) => r.status === 200 });
  // A sample of what failures looked like (status 0 is a client-side error such as a timeout or a refused connection).
  if (res.status !== 200 && Math.random() < 0.02) console.error(`poll failed: status=${res.status} error=${res.error_code} ${res.error}`);
  sleep(POLL_MS / 1000);
}
