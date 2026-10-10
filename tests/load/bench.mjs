// Load generator for the SSE-vs-polling comparison (docs/sse-vs-polling-benchmark.md).
//
// Each simulated user is one signed-in browser tab with its own connection(s) and cookie, doing what
// web/src behaves like in the chosen mode:
//
//   polling : inbox poll every 10 s; users waiting on an upload also poll their video every 2 s
//   sse     : one event stream; inbox poll every 60 s as the safety net; an inbox fetch 300 ms after a
//             hint and on every (re)connect; waiting users still poll their video every 2 s
//
//   node tests/load/bench.mjs steady    --mode sse --users 4000 --waiting 0.1 --out r.json
//   node tests/load/bench.mjs latency   --mode sse --users 2000 --events 200 --out r.json
//   node tests/load/bench.mjs burst     --mode sse --users 2000 --events 1000 --out r.json
//   node tests/load/bench.mjs reconnect --mode sse --users 4000 --out r.json
//   node tests/load/bench.mjs leak      --cycles 100000 --out r.json
//
// Accounts come from seed.mjs. METRICS_SCRAPE_TOKEN is read from .env for the server-side counters.
import http from 'node:http';
import { fork } from 'node:child_process';
import os from 'node:os';
import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';

// Started before anything else: see helper.mjs.
const helper = fork(new URL('./helper.mjs', import.meta.url));
const pending = new Map();
let helperSeq = 0;
await new Promise((resolve) => helper.once('message', resolve));
helper.on('message', ({ id, out, err }) => { const p = pending.get(id); if (p) { pending.delete(id); err ? p.reject(new Error(err)) : p.resolve(out); } });
const sh = (cmd) => new Promise((resolve, reject) => { const id = ++helperSeq; pending.set(id, { resolve, reject }); helper.send({ id, cmd }); });

const { positionals, values: A } = parseArgs({
  allowPositionals: true,
  options: {
    mode: { type: 'string', default: 'sse' },
    users: { type: 'string', default: '1000' },
    waiting: { type: 'string', default: '0' },
    warmup: { type: 'string', default: '30' },
    window: { type: 'string', default: '60' },
    events: { type: 'string', default: '200' },
    spacing: { type: 'string', default: '250' },
    cycles: { type: 'string', default: '100000' },
    concurrency: { type: 'string', default: '200' },
    ramp: { type: 'string', default: '150' },
    offset: { type: 'string', default: '0' },
    client: { type: 'string', default: 'spread' },
    // Ways to spend less on a stream (see docs/sse-vs-polling-benchmark.md, "Making SSE cheaper"):
    //  --streamFor waiting   only users waiting on an upload hold a stream; the others keep polling
    //  --videoMs 15000       how often a waiting user polls its video while its stream is up
    //  --idleInboxMs 10000   inbox cadence of users without a stream
    streamFor: { type: 'string', default: 'all' },
    videoMs: { type: 'string', default: '2000' },
    idleInboxMs: { type: 'string', default: '10000' },
    accounts: { type: 'string' },
    out: { type: 'string' },
    host: { type: 'string', default: 'localhost' },
    port: { type: 'string', default: '8080' },
  },
});
const SCENARIO = positionals[0];
const MODE = A.mode;
const PORT = Number(A.port);
const HOST = A.host;
// One macOS host has 16,384 ephemeral ports per (source, destination address, destination port). Two
// sockets per SSE user at 8000 users would need 16,000 of them on one destination, so spread the
// users over every address the backend answers on.
const lan = Object.values(os.networkInterfaces()).flat().find((i) => i.family === 'IPv4' && !i.internal)?.address;
const HOSTS = [HOST, ...(HOST === 'localhost' ? ['::1'] : []), ...(lan ? [lan] : [])].map((h) => (h === 'localhost' ? '127.0.0.1' : h));
const ROOT = new URL('../../', import.meta.url).pathname;
const ACCOUNTS = JSON.parse(readFileSync(A.accounts ?? `${process.env.BENCH_OUT}/accounts.json`, 'utf8'));
const TOKEN = readFileSync(`${ROOT}.env`, 'utf8').match(/^METRICS_SCRAPE_TOKEN=(.+)$/m)[1].trim();
const INBOX_MS = MODE === 'sse' ? 60_000 : 10_000;
// 'legacy' reconnects the way the first version of the client did (fixed ~3 s, short backoff after a refusal,
// instant refetch); 'spread' is web/src/realtime.ts with the reconnect-storm defences.
const SPREAD = A.client === 'spread';
const VIDEO_MS = 2_000;
const now = () => performance.now();

// ---------------------------------------------------------------------------------- measurement

const rec = { on: false };
const lat = {}; // kind -> [ms]
const codes = {}; // kind -> {status: n}
function record(kind, status, ms) {
  if (!rec.on) return;
  (lat[kind] ??= []).push(ms);
  const c = (codes[kind] ??= {});
  c[status] = (c[status] ?? 0) + 1;
}
function pct(arr, q) {
  if (!arr || arr.length === 0) return null;
  const s = [...arr].sort((a, b) => a - b);
  return Math.round(s[Math.min(s.length - 1, Math.floor(q * s.length))] * 10) / 10;
}
const dist = (arr) => ({ n: arr?.length ?? 0, p50: pct(arr, 0.5), p95: pct(arr, 0.95), p99: pct(arr, 0.99), max: pct(arr, 1) });

function parseProm(text) {
  const m = new Map();
  for (const line of text.split('\n')) {
    if (!line || line[0] === '#') continue;
    const i = line.lastIndexOf(' ');
    m.set(line.slice(0, i), Number(line.slice(i + 1)));
  }
  return m;
}
function sum(m, name, pred) {
  let s = 0;
  for (const [k, v] of m) {
    if (!k.startsWith(name)) continue;
    const rest = k.slice(name.length);
    if (rest !== '' && rest[0] !== '{') continue;
    if (pred && !pred(rest)) continue;
    s += v;
  }
  return s;
}
async function scrape() {
  const res = await fetch(`http://${HOST}:${PORT}/actuator/prometheus`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!res.ok) throw new Error(`scrape ${res.status}`);
  return parseProm(await res.text());
}
const uriCount = (m, uri) =>
  sum(m, 'http_server_requests_seconds_count', (l) => l.includes(`uri="${uri}"`));
const uriSeconds = (m, uri) =>
  sum(m, 'http_server_requests_seconds_sum', (l) => l.includes(`uri="${uri}"`));

async function backendPid() {
  return (await sh(`lsof -ti :${PORT} -sTCP:LISTEN | head -1`)).trim();
}
async function cpuSeconds(pid) {
  const t = (await sh(`ps -o cputime= -p ${pid}`)).trim(); // [h:]mm:ss.cc
  return t.split(':').map(Number).reduce((acc, p) => acc * 60 + p, 0);
}
async function rssMb(pid) {
  return Number((await sh(`ps -o rss= -p ${pid}`)).trim()) / 1024;
}
async function established() {
  // Server side of the sockets: local address is :PORT.
  return Number((await sh(`netstat -an -p tcp | awk '$4 ~ /\\.${PORT}$/ && $6=="ESTABLISHED"' | wc -l`)).trim());
}
async function redisCommands() {
  return Number((await sh(`docker exec sv-redis redis-cli info stats | grep total_commands_processed | cut -d: -f2`)).trim());
}
async function pgTransactions() {
  return Number((await sh(
    `docker exec sv-postgres psql -U shortvideo -d short_video_bench -tAc "select xact_commit+xact_rollback from pg_stat_database where datname='short_video_bench'"`,
  )).trim());
}
const heapUsed = (m) => sum(m, 'jvm_memory_used_bytes', (l) => l.includes('area="heap"'));

async function snapshot() {
  const pid = await backendPid();
  const m = await scrape();
  return {
    t: now(), pid, m, cpu: await cpuSeconds(pid), rss: await rssMb(pid),
    redis: await redisCommands(), pg: await pgTransactions(), clientCpu: process.cpuUsage(),
  };
}

// ---------------------------------------------------------------------------------- simulated users

class User {
  constructor(i) {
    const a = ACCOUNTS[i];
    this.i = i;
    this.accountId = a.accountId;
    this.cookie = a.cookie;
    this.videoId = a.videoId;
    this.host = HOSTS[i % HOSTS.length];
    this.agent = new http.Agent({ keepAlive: true, maxSockets: 2 });
    this.timers = new Set();
    this.stopped = false;
    this.streamOpen = false;
    this.lastInboxOk = 0;
    this.watch = null; // {sentAt, onHint, onNoticed} while an event is awaited
    this.streams = false; // holds an event stream
    this.waiting = false;
  }

  later(fn, ms) {
    const t = setTimeout(() => { this.timers.delete(t); if (!this.stopped) fn(); }, ms);
    this.timers.add(t);
  }

  req(kind, path, method = 'GET', parse = false) {
    return new Promise((resolve) => {
      const start = now();
      const r = http.request({ host: this.host, port: PORT, path, method, agent: this.agent, headers: { Cookie: this.cookie }, timeout: 15_000 }, (res) => {
        const chunks = [];
        res.on('data', (c) => parse && chunks.push(c));
        res.on('end', () => {
          const ms = now() - start;
          record(kind, res.statusCode, ms);
          let body = null;
          if (parse && chunks.length) { try { body = JSON.parse(Buffer.concat(chunks).toString()); } catch { /* not json */ } }
          resolve({ status: res.statusCode, ms, body });
        });
      });
      r.on('timeout', () => { r.destroy(new Error('timeout')); });
      r.on('error', (e) => { record(kind, e.code ?? e.message, now() - start); resolve({ status: 0, ms: now() - start, body: null }); });
      r.end();
    });
  }

  async inbox() {
    const r = await this.req('inbox', '/api/v1/notifications', 'GET', this.watch !== null);
    if (r.status === 200) {
      this.lastInboxOk = now();
      if (this.watch && r.body && r.body.unreadCount > this.watch.baseline) this.watch.onNoticed?.(now());
    }
    return r;
  }

  startPolling(waiting) {
    // Page load: an immediate inbox fetch, then the steady cadence. With a stream up the inbox is only a
    // safety net (60 s); without one it is the way the user hears about anything (10 s by default).
    const inboxMs = this.streams ? 60_000 : MODE === 'sse' ? Number(A.idleInboxMs) : INBOX_MS;
    const tick = () => { void this.inbox(); this.later(tick, inboxMs); };
    void this.inbox();
    this.later(tick, inboxMs);
    this.waiting = waiting;
    if (waiting && this.videoId) {
      // A hint already announces READY or FAILED, so with a stream up the poll is a safety net too.
      const videoMs = this.streams ? Number(A.videoMs) : VIDEO_MS;
      const vtick = () => { void this.req('video', `/api/v1/videos/${this.videoId}`); this.later(vtick, videoMs); };
      this.later(vtick, Math.random() * videoMs);
    }
  }

  // ---- event stream (what web/src/realtime.ts does, with EventSource's own automatic retry)
  retryMs = 3000; // EventSource's default until the server says otherwise
  failures = 0;

  /** The wait after the server refused the stream: web/src/realtime.ts's full-jitter window. */
  refusedDelay() {
    if (!SPREAD) return 5_000 + Math.random() * 25_000;
    this.failures += 1;
    const window = Math.min(60_000, 10_000 * 2 ** (this.failures - 1));
    return Math.max(1_000, Math.random() * window);
  }

  openStream() {
    if (this.stopped) return;
    const start = now();
    this.streamReq = http.get(
      { host: this.host, port: PORT, path: '/api/v1/events/stream', agent: this.agent, headers: { Cookie: this.cookie, Accept: 'text/event-stream' } },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          record('stream-open', res.statusCode, now() - start);
          this.streamReq = null;
          this.later(() => this.openStream(), this.refusedDelay());
          return;
        }
        res.setEncoding('utf8');
        let buf = '';
        let event = '';
        let reconnect = true;
        let after = 0;
        res.on('data', (chunk) => {
          buf += chunk;
          let nl;
          while ((nl = buf.indexOf('\n')) >= 0) {
            const line = buf.slice(0, nl);
            buf = buf.slice(nl + 1);
            if (line === ':connected') {
              this.streamOpen = true;
              record('stream-open', 200, now() - start);
              this.connectedAt = now();
              // The app reloads on every open; the spread client puts that off by up to 5 s.
              if (SPREAD) this.later(() => void this.inbox(), Math.random() * 5_000);
              else void this.inbox();
              if (SPREAD) this.later(() => { this.failures = 0; }, 30_000); // forgiven only once stable
            } else if (line.startsWith('retry:')) {
              if (SPREAD) this.retryMs = Number(line.slice(6)) || this.retryMs;
            } else if (line.startsWith('event:')) {
              event = line.slice(6).trim();
              if (event === 'changed') this.hinted(now());
            } else if (line.startsWith('data:') && event === 'bye') {
              try { ({ reconnect, after } = JSON.parse(line.slice(5))); } catch { /* ignore */ }
            }
          }
        });
        const gone = () => {
          if (!this.streamOpen && !this.streamReq) return;
          this.streamOpen = false;
          this.streamReq = null;
          if (SPREAD && event === 'bye' && !reconnect) return; // told not to come back
          const wait = SPREAD && event === 'bye' ? after + Math.random() * 2_000 : this.retryMs + (SPREAD ? 0 : Math.random() * 300);
          this.later(() => this.openStream(), wait);
        };
        res.on('end', gone);
        res.on('close', gone);
        res.on('error', gone);
      },
    );
    this.streamReq.on('error', (e) => {
      record('stream-open', e.code ?? e.message, now() - start);
      this.streamOpen = false;
      this.streamReq = null;
      // A connection error is the browser's own retry, after the delay the server last gave it.
      this.later(() => this.openStream(), this.retryMs + (SPREAD ? 0 : Math.random() * 300));
    });
  }

  hinted(at) {
    this.watch?.onHint?.(at);
    if (this.debounce) return;
    this.debounce = setTimeout(() => { this.debounce = null; void this.inbox(); }, 300);
  }

  stop() {
    this.stopped = true;
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
    if (this.debounce) clearTimeout(this.debounce);
    this.streamReq?.destroy();
    this.agent.destroy();
  }
}

async function startUsers(n, waitingFrac) {
  const users = [];
  const waitingN = Math.round(n * waitingFrac);
  const gap = 1000 / Number(A.ramp);
  for (let i = 0; i < n; i++) {
    const u = new User(i);
    users.push(u);
    u.streams = MODE === 'sse' && (A.streamFor === 'all' || i < waitingN);
    u.startPolling(i < waitingN);
    if (u.streams) u.openStream();
    if (i % 10 === 9) await sleep(gap * 10);
  }
  return users;
}

// ---------------------------------------------------------------------------------- scenarios

function delta(a, b, uriList) {
  const secs = (b.t - a.t) / 1000;
  const per = {};
  for (const uri of uriList) {
    const n = uriCount(b.m, uri) - uriCount(a.m, uri);
    const s = uriSeconds(b.m, uri) - uriSeconds(a.m, uri);
    per[uri] = { rps: Math.round((n / secs) * 10) / 10, serverAvgMs: n ? Math.round((s / n) * 1000 * 10) / 10 : null };
  }
  const totalReq = sum(b.m, 'http_server_requests_seconds_count') - sum(a.m, 'http_server_requests_seconds_count');
  return {
    seconds: Math.round(secs),
    perUri: per,
    totalServerRps: Math.round((totalReq / secs) * 10) / 10,
    backendCpuCores: Math.round(((b.cpu - a.cpu) / secs) * 100) / 100,
    clientCpuCores: Math.round((((b.clientCpu.user + b.clientCpu.system) - (a.clientCpu.user + a.clientCpu.system)) / 1e6 / secs) * 100) / 100,
    gcPauseMsPerSec: Math.round(((sum(b.m, 'jvm_gc_pause_seconds_sum') - sum(a.m, 'jvm_gc_pause_seconds_sum')) / secs) * 1000 * 10) / 10,
    redisOpsPerSec: Math.round((b.redis - a.redis) / secs),
    pgTxPerSec: Math.round((b.pg - a.pg) / secs),
    rssMb: Math.round(b.rss),
    heapUsedMb: Math.round(heapUsed(b.m) / 1048576),
    threads: sum(b.m, 'jvm_threads_live_threads'),
    openFiles: sum(b.m, 'process_files_open_files'),
  };
}

const URIS = ['/api/v1/notifications', '/api/v1/videos/{videoId}'];

async function sampleDuring(ms, everyMs = 5000) {
  const out = { hikariActiveMax: 0, hikariPendingMax: 0, established: [], realtimeConnections: 0 };
  const end = now() + ms;
  while (now() < end) {
    await sleep(Math.min(everyMs, Math.max(0, end - now())));
    const m = await scrape();
    out.hikariActiveMax = Math.max(out.hikariActiveMax, sum(m, 'hikaricp_connections_active'));
    out.hikariPendingMax = Math.max(out.hikariPendingMax, sum(m, 'hikaricp_connections_pending'));
    out.realtimeConnections = sum(m, 'realtime_connections');
    out.established.push(await established());
  }
  return out;
}

async function steady() {
  const n = Number(A.users);
  const users = await startUsers(n, Number(A.waiting));
  await sleep(Number(A.warmup) * 1000);
  const a = await snapshot();
  rec.on = true;
  const samples = await sampleDuring(Number(A.window) * 1000);
  rec.on = false;
  const b = await snapshot();
  // What the heap holds once the garbage is gone: `heapUsedMb` above includes whatever has not been
  // collected yet, which is lower the more often a collection happened to run and so says little.
  await sh(`jcmd ${b.pid} GC.run`);
  await sleep(2000);
  const afterGc = await scrape();
  const result = {
    scenario: 'steady', mode: MODE, users: n, waitingFraction: Number(A.waiting), warmupS: Number(A.warmup),
    heapAfterGcMb: Math.round(heapUsed(afterGc) / 1048576),
    rssAfterGcMb: Math.round(await rssMb(b.pid)),
    server: delta(a, b, URIS),
    samples,
    realtime: {
      connections: sum(b.m, 'realtime_connections'),
      opened: sum(b.m, 'realtime_opened_total'),
      rejected: sum(b.m, 'realtime_rejected_total'),
      sendFailed: sum(b.m, 'realtime_send_failed_total'),
    },
    client: Object.fromEntries(Object.keys(lat).map((k) => [k, { ...dist(lat[k]), codes: codes[k] }])),
    streamsOpenAtEnd: users.filter((u) => u.streamOpen).length,
  };
  users.forEach((u) => u.stop());
  return result;
}

async function fireFollow(follower, target) {
  const start = now();
  const r = await follower.req('follow', `/api/v1/creators/${target.accountId}/follow`, 'POST');
  return { sentAt: start, status: r.status };
}

async function latency() {
  const n = Number(A.users);
  const events = Number(A.events);
  const users = await startUsers(n, 0);
  await sleep(Number(A.warmup) * 1000);
  rec.on = true;
  const hint = [];
  const noticed = [];
  const failures = [];
  let notified = 0;
  const followers = [];
  for (let i = 0; i < events; i++) {
    const target = users[i];
    // A follower that has never followed this target: following again is a no-op that raises no event,
    // so a pair reused across runs (or modes) would measure nothing. --offset keeps runs apart.
    const follower = new User(n + Number(A.offset) + i);
    followers.push(follower);
    const baseline = (await target.req('baseline', '/api/v1/notifications', 'GET', true)).body?.unreadCount ?? 0;
    target.watch = { baseline };
    const sent = now();
    target.watch.onHint = (at) => { hint.push(at - sent); target.watch.onHint = null; };
    target.watch.onNoticed = (at) => { noticed.push(at - sent); notified++; target.watch = null; };
    const r = await fireFollow(follower, target);
    if (r.status !== 204) failures.push(r.status);
    await sleep(Number(A.spacing));
  }
  await sleep(MODE === 'sse' ? 8_000 : 14_000);
  rec.on = false;
  const result = {
    scenario: 'latency', mode: MODE, users: n, events, followFailures: failures.length,
    received: notified, hintMs: dist(hint), noticedMs: dist(noticed),
  };
  followers.forEach((u) => u.stop());
  users.forEach((u) => u.stop());
  return result;
}

async function burst() {
  const n = Number(A.users);
  const events = Number(A.events);
  const users = await startUsers(n, 0);
  await sleep(Number(A.warmup) * 1000);
  const samples = [];
  let sampling = true;
  const pid = await backendPid();
  const sampler = (async () => {
    let prev = { m: await scrape(), cpu: await cpuSeconds(pid), t: now() };
    while (sampling) {
      await sleep(1000);
      const m = await scrape(); const cpu = await cpuSeconds(pid); const t = now();
      const dt = (t - prev.t) / 1000;
      samples.push({
        t: Math.round(t),
        inboxRps: Math.round((uriCount(m, '/api/v1/notifications') - uriCount(prev.m, '/api/v1/notifications')) / dt),
        cpuCores: Math.round(((cpu - prev.cpu) / dt) * 100) / 100,
        hikariActive: sum(m, 'hikaricp_connections_active'),
        hikariPending: sum(m, 'hikaricp_connections_pending'),
        sent: sum(m, 'realtime_events_sent_total'),
      });
      prev = { m, cpu, t };
    }
  })();
  await sleep(5000); // quiet baseline
  const followers = [];
  const baselines = await Promise.all(Array.from({ length: events }, (_, i) =>
    users[i].req('baseline', '/api/v1/notifications', 'GET', true).then((r) => r.body?.unreadCount ?? 0)));
  rec.on = true;
  const hint = []; const noticed = []; const failures = [];
  const t0 = now();
  const sends = [];
  for (let i = 0; i < events; i++) {
    const target = users[i];
    const follower = new User(n + Number(A.offset) + i); // see latency(): a pair must be new to raise an event
    followers.push(follower);
    target.watch = { baseline: baselines[i] };
    const delay = (i * 1000) / events;
    sends.push((async () => {
      await sleep(Math.max(0, t0 + delay - now()));
      const sent = now();
      target.watch.onHint = (at) => { hint.push(at - sent); };
      target.watch.onNoticed = (at) => { noticed.push(at - sent); target.watch = null; };
      const r = await fireFollow(follower, target);
      if (r.status !== 204) failures.push(r.status);
    })());
  }
  await Promise.all(sends);
  const sendWindowMs = Math.round(now() - t0);
  await sleep(MODE === 'sse' ? 15_000 : 16_000);
  rec.on = false;
  sampling = false;
  await sampler;
  const result = {
    scenario: 'burst', mode: MODE, users: n, events, sendWindowMs, followFailures: failures.length,
    received: noticed.length, hintMs: dist(hint), noticedMs: dist(noticed),
    peak: {
      inboxRps: Math.max(...samples.map((s) => s.inboxRps)),
      cpuCores: Math.max(...samples.map((s) => s.cpuCores)),
      hikariActive: Math.max(...samples.map((s) => s.hikariActive)),
      hikariPending: Math.max(...samples.map((s) => s.hikariPending)),
    },
    baselineInboxRps: samples.slice(0, 4).reduce((a, s) => a + s.inboxRps, 0) / 4,
    timeline: samples,
    client: Object.fromEntries(Object.keys(lat).map((k) => [k, { ...dist(lat[k]), codes: codes[k] }])),
  };
  followers.forEach((u) => u.stop());
  users.forEach((u) => u.stop());
  return result;
}

async function reconnect() {
  const n = Number(A.users);
  const users = n > 0 ? await startUsers(n, 0) : [];
  await sleep(Number(A.warmup) * 1000);
  const healthy = () => users.filter((u) => (MODE === 'sse' ? u.streamOpen : now() - u.lastInboxOk < 12_000)).length;
  const before = healthy();
  const timeline = [];
  let tUp = null;
  let sampling = true;
  const tStart = now();
  const pid0 = await backendPid();
  const sampler = (async () => {
    while (sampling) {
      timeline.push({ t: Math.round(now() - tStart), healthy: healthy() });
      await sleep(500);
    }
  })();
  rec.on = true;
  // A user who is not part of the crowd and only asks for its inbox, to see what the storm does to REST.
  const probe = new User(ACCOUNTS.length - 1);
  const probeLat = []; const probeErr = {};
  let probing = false;
  const probeLoop = (async () => {
    while (sampling) {
      if (probing) {
        const r = await probe.req('probe', '/api/v1/notifications');
        if (r.status === 200) probeLat.push(r.ms); else probeErr[r.status] = (probeErr[r.status] ?? 0) + 1;
      }
      await sleep(250);
    }
  })();
  await sh(`${ROOT}tests/load/backend.sh stop`);
  const tDown = now();
  await sh(`${ROOT}tests/load/backend.sh start ${MODE}`);
  tUp = now();
  probing = true;
  // Peak CPU and DB pool use during the minute after the server is back.
  const pid = await backendPid();
  let cpuPrev = await cpuSeconds(pid); let tPrev = now();
  const post = { cpuPeakCores: 0, hikariActivePeak: 0, hikariPendingPeak: 0, inboxRpsPeak: 0 };
  let prevMetrics = await scrape();
  const recoveredAt = { p90: null, p99: null };
  while (now() - tUp < 60_000) {
    await sleep(1000);
    const cpu = await cpuSeconds(pid); const t = now(); const m = await scrape();
    const dt = (t - tPrev) / 1000;
    post.cpuPeakCores = Math.max(post.cpuPeakCores, (cpu - cpuPrev) / dt);
    post.hikariActivePeak = Math.max(post.hikariActivePeak, sum(m, 'hikaricp_connections_active'));
    post.hikariPendingPeak = Math.max(post.hikariPendingPeak, sum(m, 'hikaricp_connections_pending'));
    post.inboxRpsPeak = Math.max(post.inboxRpsPeak, (uriCount(m, '/api/v1/notifications') - uriCount(prevMetrics, '/api/v1/notifications')) / dt);
    cpuPrev = cpu; tPrev = t; prevMetrics = m;
    const h = healthy();
    if (recoveredAt.p90 === null && h >= 0.9 * before) recoveredAt.p90 = Math.round((now() - tUp) / 100) / 10;
    if (recoveredAt.p99 === null && h >= 0.99 * before) recoveredAt.p99 = Math.round((now() - tUp) / 100) / 10;
  }
  rec.on = false;
  sampling = false;
  await sampler;
  await probeLoop;
  probe.stop();
  const result = {
    scenario: 'reconnect', mode: MODE, users: n, healthyBefore: before, healthyAfter60s: healthy(),
    downtimeS: Math.round((tUp - tDown) / 100) / 10,
    secondsAfterUpToHealthy: recoveredAt,
    restProbe: { ...dist(probeLat), errors: probeErr },
    client: A.client,
    post: Object.fromEntries(Object.entries(post).map(([k, v]) => [k, Math.round(v * 100) / 100])),
    client: Object.fromEntries(Object.keys(lat).map((k) => [k, { n: lat[k].length, codes: codes[k] }])),
    timelineEveryS: timeline.filter((_, i) => i % 4 === 0),
    oldPid: pid0, newPid: pid,
  };
  users.forEach((u) => u.stop());
  return result;
}

async function leak() {
  const cycles = Number(A.cycles);
  const conc = Number(A.concurrency);
  const pid = await backendPid();
  const gc = () => sh(`jcmd ${pid} GC.run`);
  const mem = async (label) => {
    await gc();
    await sleep(1500);
    const m = await scrape();
    return { label, rssMb: Math.round(await rssMb(pid)), heapMb: Math.round(heapUsed(m) / 1048576 * 10) / 10, connections: sum(m, 'realtime_connections'), threads: sum(m, 'jvm_threads_live_threads'), files: sum(m, 'process_files_open_files') };
  };
  const report = { scenario: 'leak', cycles, concurrency: conc, steps: [] };
  report.steps.push(await mem('baseline'));

  // Per-connection cost: hold K streams, force a GC, compare against the baseline.
  for (const hold of [1000, 3000, 6000]) {
    const holders = [];
    const target = hold;
    for (let i = 0; i < target; i++) {
      const u = new User(i % ACCOUNTS.length); // <= 3 per account would evict; use distinct accounts
      u.accountIndex = i;
      holders.push(u);
    }
    // distinct accounts only
    holders.forEach((u, i) => { u.cookie = ACCOUNTS[i].cookie; u.accountId = ACCOUNTS[i].accountId; });
    for (let i = 0; i < holders.length; i++) { holders[i].openStream(); if (i % 20 === 19) await sleep(100); }
    await sleep(8000);
    const step = await mem(`holding ${hold}`);
    step.open = holders.filter((u) => u.streamOpen).length;
    report.steps.push(step);
    holders.forEach((u) => u.stop());
    await sleep(25_000); // the server finds out at its next heartbeat
    report.steps.push(await mem(`released ${hold}`));
  }

  // Churn: open, read the first line, drop; many times.
  let done = 0; let failed = 0; let peak = 0;
  const t0 = now();
  const worker = async (w) => {
    while (true) {
      const i = done++;
      if (i >= cycles) return;
      const acct = ACCOUNTS[(w * 31 + i) % ACCOUNTS.length];
      await new Promise((resolve) => {
        const r = http.get({ host: HOSTS[(w + i) % HOSTS.length], port: PORT, path: '/api/v1/events/stream', headers: { Cookie: acct.cookie, Accept: 'text/event-stream' }, agent: false, timeout: 15_000 }, (res) => {
          if (res.statusCode !== 200) { failed++; res.resume(); res.on('end', resolve); return; }
          res.once('data', () => { res.destroy(); resolve(); });
        });
        r.on('timeout', () => r.destroy(new Error('timeout')));
        r.on('error', () => { failed++; resolve(); });
      });
    }
  };
  const monitor = (async () => { while (done < cycles) { await sleep(5000); try { peak = Math.max(peak, sum(await scrape(), 'realtime_connections')); } catch { /* ignore */ } } })();
  await Promise.all(Array.from({ length: conc }, (_, w) => worker(w)));
  await monitor;
  report.churn = { seconds: Math.round((now() - t0) / 1000), failed, peakConnections: peak };
  await sleep(30_000);
  report.steps.push(await mem('after churn'));
  return report;
}

const result = await ({ steady, latency, burst, reconnect, leak }[SCENARIO] ?? (() => { throw new Error(`unknown scenario ${SCENARIO}`); }))();
console.log(JSON.stringify(result, null, 2));
if (A.out) writeFileSync(A.out, JSON.stringify(result, null, 2));
helper.kill();
process.exit(0);
