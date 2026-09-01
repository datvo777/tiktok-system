// Milestone 8 (brief section 20): "Polling load from concurrent uploads
// stays within the local target."
//
// Run:
//   BASE_URL=http://localhost:8080 k6 run tests/k6/upload-poll-load.js
//
// Each VU registers a fresh account, uploads the fixture video, then polls
// GET /api/v1/videos/{id} the same way web/src/Upload.tsx does until READY,
// measuring total time-to-ready and the polling request rate under
// concurrency. Each iteration does a real transcode, so keep VU count modest
// -- this is meant to size polling load, not to load-test the media worker.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VIDEO_FILE = open('./fixtures/sample.mp4', 'b');

export const options = {
  scenarios: {
    concurrent_uploads: {
      executor: 'per-vu-iterations',
      vus: 5,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    // Local target: a small local fixture should reach READY well inside
    // the media-worker's own polling ceiling (10 minutes, per Upload.tsx).
    time_to_ready: ['p(95)<60000'],
  },
};

const timeToReady = new Trend('time_to_ready', true);

export default function () {
  const email = `k6-upload-${__VU}-${Date.now()}@example.com`;
  const password = 'Password123!';

  const register = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({ email, password, displayName: `k6 VU ${__VU}` }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(register, { registered: (r) => r.status === 201 || r.status === 200 });

  const login = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(login, { 'logged in': (r) => r.status === 200 });
  const cookie = `sv_session=${login.cookies['sv_session'][0].value}`;

  const createUpload = http.post(`${BASE_URL}/api/v1/uploads`, JSON.stringify({}), {
    headers: { Cookie: cookie, 'Content-Type': 'application/json' },
  });
  check(createUpload, { 'upload session created': (r) => r.status === 200 || r.status === 201 });
  const { uploadId, videoId, uploadUrl } = createUpload.json();

  const put = http.put(uploadUrl, VIDEO_FILE, { headers: { 'Content-Type': 'video/mp4' } });
  check(put, { 'uploaded to storage': (r) => r.status === 200 });

  const complete = http.post(`${BASE_URL}/api/v1/uploads/${uploadId}/complete`, null, {
    headers: { Cookie: cookie },
  });
  check(complete, { 'upload completed': (r) => r.status === 200 });

  const start = Date.now();
  let ready = false;
  for (let i = 0; i < 60; i++) {
    const status = http.get(`${BASE_URL}/api/v1/videos/${videoId}`, { headers: { Cookie: cookie } });
    if (status.json('processingState') === 'READY') {
      ready = true;
      break;
    }
    if (status.json('processingState') === 'FAILED') {
      break;
    }
    sleep(1);
  }
  timeToReady.add(Date.now() - start);
  check(null, { 'reached READY before giving up': () => ready });
}
