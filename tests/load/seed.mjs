// Creates the accounts the benchmark signs in as, once, and writes their session cookies to a file.
//
//   node tests/load/seed.mjs --count 8000 --waiting 800 --out /tmp/sv-bench/accounts.json
//
// One account per simulated user, so the JWT filter's per-account caches are not artificially hot.
// The first --waiting accounts also get a draft video (the thing an uploader polls for).
// Registration/login rate limits must be off on the target backend (see backend.sh).
import { writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    base: { type: 'string', default: 'http://localhost:8080' },
    count: { type: 'string', default: '8000' },
    waiting: { type: 'string', default: '800' },
    concurrency: { type: 'string', default: '16' },
    out: { type: 'string' },
    prefix: { type: 'string', default: 'bench' },
  },
});
const BASE = values.base;
const COUNT = Number(values.count);
const WAITING = Number(values.waiting);
const stamp = Date.now();
const json = { 'Content-Type': 'application/json' };

async function one(i) {
  const email = `${values.prefix}-${stamp}-${i}@example.com`;
  const password = 'correct-horse-battery';
  const reg = await fetch(`${BASE}/api/v1/accounts`, {
    method: 'POST', headers: json, body: JSON.stringify({ email, password, displayName: `Bench ${i}` }),
  });
  if (!reg.ok) throw new Error(`register ${i}: ${reg.status} ${await reg.text()}`);
  const { accountId } = await reg.json();
  const login = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST', headers: json, body: JSON.stringify({ email, password }),
  });
  if (!login.ok) throw new Error(`login ${i}: ${login.status}`);
  const cookie = login.headers.getSetCookie().find((c) => c.startsWith('sv_session=')).split(';')[0];
  let videoId = null;
  if (i < WAITING) {
    const up = await fetch(`${BASE}/api/v1/uploads`, {
      method: 'POST', headers: { ...json, Cookie: cookie }, body: JSON.stringify({ title: 'bench', sizeBytes: 1048576 }),
    });
    if (!up.ok) throw new Error(`upload ${i}: ${up.status} ${await up.text()}`);
    videoId = (await up.json()).videoId;
  }
  return { accountId, cookie, videoId };
}

const accounts = new Array(COUNT);
let next = 0;
let done = 0;
const t0 = Date.now();
await Promise.all(
  Array.from({ length: Number(values.concurrency) }, async () => {
    while (true) {
      const i = next++;
      if (i >= COUNT) return;
      accounts[i] = await one(i);
      if (++done % 500 === 0) console.log(`${done}/${COUNT} (${Math.round((Date.now() - t0) / 1000)}s)`);
    }
  }),
);
writeFileSync(values.out, JSON.stringify(accounts));
console.log(`wrote ${COUNT} accounts to ${values.out} in ${Math.round((Date.now() - t0) / 1000)}s`);
