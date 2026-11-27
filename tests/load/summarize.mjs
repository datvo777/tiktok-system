// Turns the JSON files run-all.sh leaves in $BENCH_OUT/results into the tables of
// docs/sse-vs-polling-benchmark.md. Steady runs are reported as the median of the repetitions with
// the min-max range; nothing is averaged away silently.
//
//   node tests/load/summarize.mjs $BENCH_OUT/results
import { readFileSync, readdirSync } from 'node:fs';

const dir = process.argv[2];
const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
const load = (f) => JSON.parse(readFileSync(`${dir}/${f}`, 'utf8'));
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const fmt = (a, d = 2) => {
  const v = a.filter((x) => x != null);
  if (!v.length) return '-';
  const r = (x) => (Math.round(x * 10 ** d) / 10 ** d).toString();
  return v.length === 1 ? r(v[0]) : `${r(med(v))} (${r(Math.min(...v))}-${r(Math.max(...v))})`;
};

// ---- steady
const groups = {};
for (const f of files.filter((f) => f.startsWith('steady_'))) {
  const d = load(f);
  const key = `${d.users}|${d.waitingFraction}|${d.mode}`;
  (groups[key] ??= []).push(d);
}
console.log('### S1 steady state (median of reps, min-max in brackets)\n');
console.log('| Users | Waiting | Mode | Reps | Server req/s | Inbox req/s | Backend CPU (cores) | Client CPU (cores) | RSS MB | Heap MB | GC ms/s | Threads | Open files | Hikari active max | Hikari pending max | Redis ops/s | PG tx/s | Errors |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const key of Object.keys(groups).sort((a, b) => {
  const [na, wa, ma] = a.split('|'); const [nb, wb, mb] = b.split('|');
  return Number(na) - Number(nb) || Number(wa) - Number(wb) || ma.localeCompare(mb);
})) {
  const rs = groups[key];
  const [n, w, mode] = key.split('|');
  const errs = rs.map((r) => Object.values(r.client).reduce((a, k) => a + Object.entries(k.codes ?? {}).filter(([c]) => c !== '200').reduce((x, [, v]) => x + v, 0), 0));
  console.log(`| ${n} | ${Number(w) * 100}% | ${mode} | ${rs.length} | ${fmt(rs.map((r) => r.server.totalServerRps), 0)} | ${fmt(rs.map((r) => r.server.perUri['/api/v1/notifications'].rps), 1)} | ${fmt(rs.map((r) => r.server.backendCpuCores))} | ${fmt(rs.map((r) => r.server.clientCpuCores))} | ${fmt(rs.map((r) => r.server.rssMb), 0)} | ${fmt(rs.map((r) => r.server.heapUsedMb), 0)} | ${fmt(rs.map((r) => r.server.gcPauseMsPerSec), 1)} | ${fmt(rs.map((r) => r.server.threads), 0)} | ${fmt(rs.map((r) => r.server.openFiles), 0)} | ${fmt(rs.map((r) => r.samples.hikariActiveMax), 0)} | ${fmt(rs.map((r) => r.samples.hikariPendingMax), 0)} | ${fmt(rs.map((r) => r.server.redisOpsPerSec), 0)} | ${fmt(rs.map((r) => r.server.pgTxPerSec), 0)} | ${fmt(errs, 0)} |`);
}
console.log('\nClient-observed latency, ms (p50 / p95 / p99), median of reps:\n');
console.log('| Users | Waiting | Mode | inbox | video |');
console.log('|---|---|---|---|---|');
for (const key of Object.keys(groups).sort()) {
  const rs = groups[key];
  const [n, w, mode] = key.split('|');
  const cell = (k) => {
    const v = rs.map((r) => r.client[k]).filter(Boolean);
    return v.length ? `${fmt(v.map((x) => x.p50), 1)} / ${fmt(v.map((x) => x.p95), 1)} / ${fmt(v.map((x) => x.p99), 1)}` : '-';
  };
  console.log(`| ${n} | ${Number(w) * 100}% | ${mode} | ${cell('inbox')} | ${cell('video')} |`);
}

// ---- latency + burst
console.log('\n### S2 / S3 time until the target client notices (ms)\n');
console.log('| Scenario | Mode | Events | Received | hint p50 / p95 | noticed p50 / p95 / p99 / max | Follow failures | Peak inbox req/s (baseline) | Peak CPU cores | Hikari active / pending peak |');
console.log('|---|---|---|---|---|---|---|---|---|---|');
for (const f of files.filter((f) => f.startsWith('latency_') || f.startsWith('burst_'))) {
  const d = load(f);
  const n = d.noticedMs;
  console.log(`| ${d.scenario}${d.scenario === 'burst' ? ` (${d.sendWindowMs} ms send window)` : ''} | ${d.mode} | ${d.events} | ${d.received} | ${d.hintMs.n ? `${d.hintMs.p50} / ${d.hintMs.p95}` : '-'} | ${n.p50} / ${n.p95} / ${n.p99} / ${n.max} | ${d.followFailures} | ${d.peak ? `${d.peak.inboxRps} (${Math.round(d.baselineInboxRps)})` : '-'} | ${d.peak?.cpuCores ?? '-'} | ${d.peak ? `${d.peak.hikariActive} / ${d.peak.hikariPending}` : '-'} |`);
}

// ---- reconnect
console.log('\n### S4 restart with connected users\n');
console.log('| Mode | Users | Healthy before | Downtime s | s after up to 90% | to 99% | Healthy after 60 s | CPU peak cores | Hikari active / pending peak | Inbox req/s peak | Client errors (by kind) |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|');
for (const f of files.filter((f) => f.startsWith('reconnect_'))) {
  const d = load(f);
  const errs = Object.entries(d.client).map(([k, v]) => `${k}: ${Object.entries(v.codes ?? {}).filter(([c]) => c !== '200').map(([c, n]) => `${c}x${n}`).join(' ') || 'none'}`).join('; ');
  console.log(`| ${d.mode} | ${d.users} | ${d.healthyBefore} | ${d.downtimeS} | ${d.secondsAfterUpToHealthy.p90 ?? '-'} | ${d.secondsAfterUpToHealthy.p99 ?? '-'} | ${d.healthyAfter60s} | ${d.post.cpuPeakCores} | ${d.post.hikariActivePeak} / ${d.post.hikariPendingPeak} | ${d.post.inboxRpsPeak} | ${errs || '-'} |`);
}

// ---- leak
const leak = files.includes('leak.json') ? load('leak.json') : null;
if (leak) {
  console.log('\n### S3/S6 memory per connection and leak check\n');
  console.log('| Step | RSS MB | Heap MB (after GC) | realtime.connections | Threads | Open files |');
  console.log('|---|---|---|---|---|---|');
  for (const s of leak.steps) console.log(`| ${s.label}${s.open != null ? ` (clients open: ${s.open})` : ''} | ${s.rssMb} | ${s.heapMb} | ${s.connections} | ${s.threads} | ${s.files} |`);
  console.log(`\nChurn: ${leak.cycles} open/close cycles at concurrency ${leak.concurrency} in ${leak.churn.seconds} s, ${leak.churn.failed} failed, peak connections ${leak.churn.peakConnections}.`);
}
