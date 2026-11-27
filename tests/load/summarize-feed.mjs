// Turns the JSON files run-feed.sh leaves into one table and checks each cell against the pass criteria
// (the plan's section 4): errors under 1%, Hikari pending at most 5, page-0 p95 at most twice the lowest
// load of the same profile and sign-in share, no eviction, memory at most 70% of maxmemory. The memory
// check uses the larger of what was measured and what the ranking keys will reach at the real TTL.
//
//   node tests/load/summarize-feed.mjs $BENCH_OUT/results-feed
import { readFileSync, readdirSync } from 'node:fs';

const dir = process.argv[2];
const runs = readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(readFileSync(`${dir}/${f}`, 'utf8')));
const med = (a) => { const v = a.filter((x) => x != null).sort((x, y) => x - y); return v.length ? v[Math.floor(v.length / 2)] : null; };
const r1 = (x) => (x == null ? '-' : String(Math.round(x * 10) / 10));
const mb = (x) => (x == null ? '-' : String(Math.round(x / 1048576)));

const groups = new Map();
for (const d of runs) {
  const k = `${d.profile}|${d.signedInFraction}|${d.users}`;
  if (!groups.has(k)) groups.set(k, []);
  groups.get(k).push(d);
}
const cells = [...groups.entries()].map(([k, reps]) => {
  const [profile, share, users] = k.split('|');
  const p95 = (d) => d.client.feed_page0_in?.p95 ?? d.client.feed_page0_anon?.p95 ?? null;
  const pageN95 = (d) => d.client.feed_pageN_in?.p95 ?? d.client.feed_pageN_anon?.p95 ?? null;
  return {
    profile, share: Number(share), users: Number(users), reps: reps.length,
    err: med(reps.map((d) => d.errorRate)), p95p0: med(reps.map(p95)), p95pN: med(reps.map(pageN95)),
    rps: med(reps.map((d) => d.server.feedRps)), pg: med(reps.map((d) => d.server.pgTxPerSec)),
    cpu: med(reps.map((d) => d.server.backendCpuCores)), pending: Math.max(...reps.map((d) => d.server.hikariPendingMax)),
    active: Math.max(...reps.map((d) => d.server.hikariActiveMax)),
    keyKb: med(reps.map((d) => d.redis.keySize.meanBytes != null ? d.redis.keySize.meanBytes / 1024 : null)),
    mem: Math.max(...reps.map((d) => d.redis.usedMemoryFractionOfMax ?? 0)),
    proj: Math.max(...reps.map((d) => d.projection.fractionOfMaxmemory ?? 0)),
    projBytes: med(reps.map((d) => d.projection.steadyStateRankingBytes)),
    evicted: Math.max(...reps.map((d) => d.redis.evictedKeysDuringWindow)),
    hit: med(reps.map((d) => d.redis.keyspaceHitRatio)),
  };
});
const baseline = new Map();
for (const c of cells) {
  const k = `${c.profile}|${c.share}`;
  if (!baseline.has(k) || c.users < baseline.get(k).users) baseline.set(k, c);
}
console.log('| Profile | Signed in | Viewers | Reps | Feed req/s | p95 page 0 ms | p95 page N ms | Err % | PG tx/s | CPU cores | Hikari act/pend | Key KB | Redis mem % (measured / at TTL) | Evicted | Redis hit ratio | Verdict |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const c of cells.sort((a, b) => a.profile.localeCompare(b.profile) || b.share - a.share || a.users - b.users)) {
  const base = baseline.get(`${c.profile}|${c.share}`);
  const fails = [];
  if ((c.err ?? 0) > 0.01) fails.push('errors');
  if (c.pending > 5) fails.push('pool pending');
  if (base !== c && base.p95p0 != null && c.p95p0 != null && c.p95p0 > 2 * base.p95p0) fails.push('p95 > 2x low-load');
  if (c.evicted > 0) fails.push('evictions');
  if (Math.max(c.mem, c.proj) > 0.7) fails.push('memory > 70%');
  console.log(`| ${c.profile} | ${c.share * 100}% | ${c.users} | ${c.reps} | ${r1(c.rps)} | ${r1(c.p95p0)} | ${r1(c.p95pN)} | ${r1((c.err ?? 0) * 100)} | ${r1(c.pg)} | ${r1(c.cpu)} | ${c.active}/${c.pending} | ${r1(c.keyKb)} | ${r1(c.mem * 100)} / ${r1(c.proj * 100)} | ${c.evicted} | ${r1(c.hit)} | ${fails.length ? 'FAIL: ' + fails.join(', ') : 'pass'} |`);
}
