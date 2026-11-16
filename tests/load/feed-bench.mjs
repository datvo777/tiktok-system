// Load generator for the feed: how many viewers the backend, its Redis and its PostgreSQL carry.
//
//   node tests/load/feed-bench.mjs --users 2000 --signedIn 1 --profile A --out r.json
//
// Each simulated viewer does what web/src/Feed.tsx does: open the feed (page 0), "watch" a video for
// --watchMs, record a view after 3 s, ask for the next page when two slides are left, and after
// --sessionVideos videos leave and come back (a new page 0, which for a signed-in viewer starts a new
// ranking). Signed-out viewers send no cookie and share one ranking.
//
// Accounts come from seed.mjs (one per signed-in viewer); videos from seed-feed.sh. Reported:
//   client   latency and status codes per request kind
//   server   feed requests/s and server-side mean, Hikari pool use, PostgreSQL transactions/s, CPU
//   redis    memory, evictions, ranking keys, key size, and the Redis command mix (GET/SET = ranking
//            reads/writes when the container is the dedicated cache)
//   projection  steady-state ranking memory at the real session TTL, from the measured rate of new
//            rankings and the measured key size. A run is a few minutes and the TTL is 30, so memory
//            has not reached its plateau when the window ends; the projection is what it will reach.
import http from 'node:http';
import { fork } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';

// Started before any socket is opened: see helper.mjs.
const helper = fork(new URL('./helper.mjs', import.meta.url));
const pending = new Map();
let helperSeq = 0;
await new Promise((resolve) => helper.once('message', resolve));
helper.on('message', ({ id, out, err }) => { const p = pending.get(id); if (p) { pending.delete(id); err ? p.reject(new Error(err)) : p.resolve(out); } });
const sh = (cmd) => new Promise((resolve, reject) => { const id = ++helperSeq; pending.set(id, { resolve, reject }); helper.send({ id, cmd }); });

const { values: A } = parseArgs({
  options: {
    users: { type: 'string', default: '500' },
    //  fraction of viewers who are signed in; the rest are strangers sharing one ranking
    signedIn: { type: 'string', default: '1' },
    watchMs: { type: 'string', default: '10000' },
    viewAfterMs: { type: 'string', default: '3000' },
    //  videos watched before the viewer leaves and comes back (a new session)
    sessionVideos: { type: 'string', default: '30' },
    gapMs: { type: 'string', default: '5000' },
    warmup: { type: 'string', default: '30' },
    window: { type: 'string', default: '90' },
    //  viewers started per second
    ramp: { type: 'string', default: '200' },
    //  label only: which seed-feed.sh profile the data is
    profile: { type: 'string', default: '?' },
    //  the ranking session TTL the projection uses (shortvideo.feed.ranking-session-ttl)
    ttlSeconds: { type: 'string', default: '1800' },
    accounts: { type: 'string' },
    host: { type: 'string', default: 'localhost' },
    port: { type: 'string', default: '8080' },
    redis: { type: 'string', default: 'sv-redis-cache' },
    postgres: { type: 'string', default: 'sv-postgres' },
    db: { type: 'string', default: 'short_video_bench' },
    out: { type: 'string' },
  },
});
const USERS = Number(A.users);
const SIGNED_IN = Math.round(USERS * Number(A.signedIn));
const WATCH_MS = Number(A.watchMs);
const VIEW_AFTER_MS = Math.min(Number(A.viewAfterMs), WATCH_MS);
const SESSION_VIDEOS = Number(A.sessionVideos);
const PORT = Number(A.port);
const ROOT = new URL('../../', import.meta.url).pathname;
const ACCOUNTS = SIGNED_IN > 0 ? JSON.parse(readFileSync(A.accounts ?? `${process.env.BENCH_OUT}/accounts.json`, 'utf8')) : [];
if (ACCOUNTS.length < SIGNED_IN) throw new Error(`need ${SIGNED_IN} accounts, ${A.accounts ?? 'accounts.json'} has ${ACCOUNTS.length} (seed.mjs --count)`);
const TOKEN = readFileSync(`${ROOT}.env`, 'utf8').match(/^METRICS_SCRAPE_TOKEN=(.+)$/m)[1].trim();
const now = () => performance.now();
const agent = new http.Agent({ keepAlive: true, maxSockets: 400 });

// ---------------------------------------------------------------------------------- measurement

const rec = { on: false };
const lat = {};
const codes = {};
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
const round = (x, d = 1) => Math.round(x * 10 ** d) / 10 ** d;

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
  const res = await fetch(`http://${A.host}:${PORT}/actuator/prometheus`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!res.ok) throw new Error(`scrape ${res.status}`);
  return parseProm(await res.text());
}
const uriCount = (m, uri) => sum(m, 'http_server_requests_seconds_count', (l) => l.includes(`uri="${uri}"`));
const uriSeconds = (m, uri) => sum(m, 'http_server_requests_seconds_sum', (l) => l.includes(`uri="${uri}"`));

const backendPid = async () => (await sh(`lsof -ti :${PORT} -sTCP:LISTEN | head -1`)).trim();
async function cpuSeconds(pid) {
  const t = (await sh(`ps -o cputime= -p ${pid}`)).trim();
  return t.split(':').map(Number).reduce((acc, p) => acc * 60 + p, 0);
}
async function pgTransactions() {
  return Number((await sh(
    `docker exec ${A.postgres} psql -U shortvideo -d ${A.db} -tAc "select xact_commit+xact_rollback from pg_stat_database where datname='${A.db}'"`,
  )).trim());
}

// Redis, read through `docker exec` like the rest of the benchmark's server-side counters.
const rcli = (args) => sh(`docker exec ${A.redis} redis-cli ${args}`);
function parseInfo(text) {
  const o = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf(':');
    if (i > 0) o[line.slice(0, i)] = line.slice(i + 1).trim();
  }
  return o;
}
const cmdCalls = (info, cmd) => Number((info[`cmdstat_${cmd}`] ?? '').match(/calls=(\d+)/)?.[1] ?? 0);
async function redisInfo() {
  return parseInfo(await rcli('info all'));
}
async function rankingKeys() {
  // SCAN, never KEYS: this runs against a Redis under load.
  return Number((await sh(`docker exec ${A.redis} sh -c "redis-cli --scan --pattern 'feed:ranking:*' | wc -l"`)).trim());
}
/** Mean MEMORY USAGE of up to `n` ranking keys: what one viewer's ranking really costs. */
async function sampleKeyBytes(n = 20) {
  const keys = (await sh(`docker exec ${A.redis} sh -c "redis-cli --scan --pattern 'feed:ranking:*' | head -${n}"`)).split('\n').filter(Boolean);
  const sizes = [];
  for (const k of keys) {
    const v = Number((await rcli(`memory usage ${k}`)).trim());
    if (Number.isFinite(v) && v > 0) sizes.push(v);
  }
  return { sampled: sizes.length, meanBytes: sizes.length ? Math.round(sizes.reduce((a, b) => a + b, 0) / sizes.length) : null, maxBytes: sizes.length ? Math.max(...sizes) : null };
}

// ---------------------------------------------------------------------------------- viewers

function request(kind, path, cookie, method = 'GET', body = null) {
  return new Promise((resolve) => {
    const start = now();
    const headers = {};
    if (cookie) headers.Cookie = cookie;
    if (body) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = Buffer.byteLength(body); }
    const r = http.request({ host: '127.0.0.1', port: PORT, path, method, agent, headers, timeout: 20_000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        record(kind, res.statusCode, now() - start);
        let json = null;
        if (res.statusCode === 200 && method === 'GET') { try { json = JSON.parse(Buffer.concat(chunks).toString()); } catch { /* not json */ } }
        resolve({ status: res.statusCode, json });
      });
    });
    r.on('timeout', () => r.destroy(new Error('timeout')));
    r.on('error', (e) => { record(kind, e.code ?? e.message, now() - start); resolve({ status: 0, json: null }); });
    r.end(body);
  });
}

class Viewer {
  constructor(i) {
    this.cookie = i < SIGNED_IN ? ACCOUNTS[i].cookie : null;
    this.tag = this.cookie ? 'in' : 'anon';
    this.stopped = false;
  }

  async page(n) {
    const r = await request(`feed_${n === 0 ? 'page0' : 'pageN'}_${this.tag}`, `/api/v1/feed?page=${n}`, this.cookie);
    return r.status === 200 && r.json ? r.json : null;
  }

  async run() {
    // The first session starts at a random point: all viewers opening the feed at the same moment would
    // leave every later page 0 for after the window, and a window of a few minutes would measure none of
    // the ranking work. The viewer still opens page 0, then catches up to its position without waiting.
    let from = Math.floor(Math.random() * SESSION_VIDEOS);
    while (!this.stopped) {
      await this.session(from);
      from = 0;
      await sleep(Number(A.gapMs));
    }
  }

  async session(from = 0) {
    const first = await this.page(0);
    if (!first) { await sleep(1000); return; }
    const items = [...first.items];
    let hasMore = first.hasMore;
    let page = 0;
    for (let index = 0; index < SESSION_VIDEOS && !this.stopped; index++) {
      if (index >= items.length) return; // reached the end of the ranking
      // web/src/Feed.tsx: the next page is asked for when two slides are left.
      if (index >= items.length - 2 && hasMore) {
        const next = await this.page(++page);
        if (next) { items.push(...next.items); hasMore = next.hasMore; } else hasMore = false;
      }
      if (index < from) continue; // catching up to where this viewer is, not watching
      await sleep(VIEW_AFTER_MS);
      if (this.cookie) {
        void request('view', `/api/v1/videos/${items[index].videoId}/views`, this.cookie, 'POST', JSON.stringify({ watchedMs: VIEW_AFTER_MS, completed: false }));
      }
      await sleep(WATCH_MS - VIEW_AFTER_MS);
    }
  }
}

// ---------------------------------------------------------------------------------- run

async function sampleDuring(ms, everyMs = 5000) {
  const out = { hikariActiveMax: 0, hikariPendingMax: 0, usedMemoryMaxBytes: 0, rankingKeysMax: 0, evictedKeysMax: 0 };
  const end = now() + ms;
  while (now() < end) {
    await sleep(Math.min(everyMs, Math.max(0, end - now())));
    const m = await scrape();
    out.hikariActiveMax = Math.max(out.hikariActiveMax, sum(m, 'hikaricp_connections_active'));
    out.hikariPendingMax = Math.max(out.hikariPendingMax, sum(m, 'hikaricp_connections_pending'));
    const info = await redisInfo();
    out.usedMemoryMaxBytes = Math.max(out.usedMemoryMaxBytes, Number(info.used_memory));
    out.evictedKeysMax = Math.max(out.evictedKeysMax, Number(info.evicted_keys));
    out.rankingKeysMax = Math.max(out.rankingKeysMax, await rankingKeys());
  }
  return out;
}

const viewers = [];
const gap = 1000 / Number(A.ramp);
for (let i = 0; i < USERS; i++) {
  const v = new Viewer(i);
  viewers.push(v);
  void v.run();
  if (i % 10 === 9) await sleep(gap * 10);
}
await sleep(Number(A.warmup) * 1000);

const pid = await backendPid();
const a = { t: now(), m: await scrape(), cpu: await cpuSeconds(pid), pg: await pgTransactions(), info: await redisInfo(), clientCpu: process.cpuUsage() };
rec.on = true;
const samples = await sampleDuring(Number(A.window) * 1000);
rec.on = false;
const b = { t: now(), m: await scrape(), cpu: await cpuSeconds(pid), pg: await pgTransactions(), info: await redisInfo(), clientCpu: process.cpuUsage() };
const keySize = await sampleKeyBytes();
viewers.forEach((v) => { v.stopped = true; });

const secs = (b.t - a.t) / 1000;
const feedUri = '/api/v1/feed';
const feedN = uriCount(b.m, feedUri) - uriCount(a.m, feedUri);
const feedS = uriSeconds(b.m, feedUri) - uriSeconds(a.m, feedUri);
const sets = cmdCalls(b.info, 'set') - cmdCalls(a.info, 'set');
const gets = cmdCalls(b.info, 'get') - cmdCalls(a.info, 'get');
const hits = Number(b.info.keyspace_hits) - Number(a.info.keyspace_hits);
const misses = Number(b.info.keyspace_misses) - Number(a.info.keyspace_misses);
const maxmemory = Number(b.info.maxmemory);
// Rankings written per second x seconds they live x bytes each. Only meaningful when the Redis is the
// dedicated cache (otherwise SET also counts the deny-list and counters) and the key size was sampled.
const projectedBytes = keySize.meanBytes ? Math.round((sets / secs) * Number(A.ttlSeconds) * keySize.meanBytes) : null;

const allCodes = Object.values(codes).flatMap((c) => Object.entries(c));
const totalReq = allCodes.reduce((s, [, n]) => s + n, 0);
const okReq = allCodes.filter(([code]) => /^2/.test(code)).reduce((s, [, n]) => s + n, 0);

const result = {
  scenario: 'feed', users: USERS, signedInFraction: Number(A.signedIn), profile: A.profile, watchMs: WATCH_MS,
  sessionVideos: SESSION_VIDEOS, warmupS: Number(A.warmup), windowS: Math.round(secs),
  errorRate: totalReq ? round((totalReq - okReq) / totalReq, 4) : null,
  client: Object.fromEntries(Object.keys(lat).map((k) => [k, { ...dist(lat[k]), codes: codes[k] }])),
  server: {
    feedRps: round(feedN / secs), feedServerMeanMs: feedN ? round((feedS / feedN) * 1000) : null,
    totalServerRps: round((sum(b.m, 'http_server_requests_seconds_count') - sum(a.m, 'http_server_requests_seconds_count')) / secs),
    pgTxPerSec: Math.round((b.pg - a.pg) / secs),
    backendCpuCores: round((b.cpu - a.cpu) / secs, 2),
    clientCpuCores: round(((b.clientCpu.user + b.clientCpu.system) - (a.clientCpu.user + a.clientCpu.system)) / 1e6 / secs, 2),
    hikariActiveMax: samples.hikariActiveMax, hikariPendingMax: samples.hikariPendingMax,
  },
  redis: {
    container: A.redis, maxmemoryBytes: maxmemory,
    usedMemoryMaxBytes: samples.usedMemoryMaxBytes,
    usedMemoryFractionOfMax: maxmemory ? round(samples.usedMemoryMaxBytes / maxmemory, 3) : null,
    evictedKeysDuringWindow: Number(b.info.evicted_keys) - Number(a.info.evicted_keys),
    rankingKeysMax: samples.rankingKeysMax, keySize,
    getsPerSec: round(gets / secs), setsPerSec: round(sets / secs),
    keyspaceHitRatio: hits + misses ? round(hits / (hits + misses), 3) : null,
  },
  projection: {
    ttlSeconds: Number(A.ttlSeconds), rankingsWrittenPerSec: round(sets / secs, 2),
    steadyStateRankingBytes: projectedBytes,
    fractionOfMaxmemory: projectedBytes && maxmemory ? round(projectedBytes / maxmemory, 3) : null,
  },
};
console.log(JSON.stringify(result, null, 2));
if (A.out) writeFileSync(A.out, JSON.stringify(result, null, 2));
agent.destroy();
helper.kill();
process.exit(0);
