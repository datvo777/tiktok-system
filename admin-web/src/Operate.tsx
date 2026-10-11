import { useQuery } from '@tanstack/react-query';
import { listPending, listPendingAppeals } from './api';
import { ageSince } from './ui';

/** One wide page is enough to size the backlog without walking every cursor. */
const PROBE = 100;

const BUCKETS = [
  { label: 'Under 15m', max: 15 * 60_000, tone: '' },
  { label: '15m – 1h', max: 60 * 60_000, tone: '' },
  { label: '1h – 6h', max: 6 * 60 * 60_000, tone: 'is-warn' },
  { label: 'Over 6h', max: Infinity, tone: 'is-crit' },
];

export function Operate() {
  const queue = useQuery({
    queryKey: ['queue-health'],
    queryFn: () => listPending(undefined, PROBE),
    refetchInterval: 20000,
  });

  const appeals = useQuery({
    queryKey: ['pending-appeals'],
    queryFn: listPendingAppeals,
    refetchInterval: 30000,
  });

  const items = queue.data?.items ?? [];
  const capped = Boolean(queue.data?.nextCursor);
  const now = Date.now();

  const ages = items.map((i) => now - new Date(i.createdAt).getTime()).filter((ms) => Number.isFinite(ms) && ms >= 0);
  const oldest = items[0];
  const overAnHour = ages.filter((ms) => ms > 60 * 60_000).length;

  const distribution = BUCKETS.map((bucket, i) => {
    const min = i === 0 ? 0 : BUCKETS[i - 1]!.max;
    return { ...bucket, count: ages.filter((ms) => ms >= min && ms < bucket.max).length };
  });

  const peak = Math.max(1, ...distribution.map((d) => d.count));

  return (
    <div className="page">
      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>Queue health</h2>
            <span className="sub">Refreshed every 20 seconds</span>
          </div>
        </div>

        <div className="panel-body">
          {queue.isError && <p className="error-text">{(queue.error as Error).message}</p>}

          <div className="statgrid">
            <div className="statcard">
              <span className="k">Awaiting moderation</span>
              <span className={`v${capped ? ' is-warn' : ''}`}>
                {items.length}
                {capped ? '+' : ''}
              </span>
              <span className="w">
                {capped ? `More than ${PROBE} — the exact depth needs a count endpoint.` : 'Full backlog.'}
              </span>
            </div>

            <div className="statcard">
              <span className="k">Oldest waiting</span>
              <span className={`v${oldest && now - new Date(oldest.createdAt).getTime() > 3600000 ? ' is-crit' : ' is-ok'}`}>
                {oldest ? ageSince(oldest.createdAt) : '—'}
              </span>
              <span className="w">The queue is strict FIFO, so this is the true worst case.</span>
            </div>

            <div className="statcard">
              <span className="k">Waiting over 1h</span>
              <span className={`v${overAnHour > 0 ? ' is-warn' : ' is-ok'}`}>{overAnHour}</span>
              <span className="w">Of the {items.length} sampled.</span>
            </div>

            <div className="statcard">
              <span className="k">Appeals awaiting</span>
              <span className={`v${(appeals.data?.length ?? 0) > 0 ? ' is-warn' : ' is-ok'}`}>
                {appeals.data?.length ?? '—'}
              </span>
              <span className="w">Reviewed on the Review surface.</span>
            </div>
          </div>
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>How long things have been waiting</h2>
            <span className="sub">Sampled from the {items.length} oldest items</span>
          </div>
        </div>

        <div className="panel-body">
          {items.length === 0 && <div className="empty">Nothing is waiting for a decision.</div>}

          {items.length > 0 && (
            <div className="bars">
              {distribution.map((bucket) => (
                <div key={bucket.label} className="bar-row">
                  <span className="bar-label">{bucket.label}</span>
                  <span className="bar-track">
                    <span
                      className={`bar-fill ${bucket.tone}`}
                      style={{ width: `${(bucket.count / peak) * 100}%` }}
                    />
                  </span>
                  <span className="bar-count">{bucket.count}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>Not measurable yet</h2>
            <span className="sub">Stated rather than faked</span>
          </div>
        </div>

        <div className="panel-body">
          <p className="section-note">
            Decision throughput, reviewer agreement and reversal-on-appeal rates are the numbers that would actually
            tell you whether moderation is working. None of them can be computed today: there is no audit log recording
            who decided what and when, and rejection reasons are free text rather than a policy enum, so they cannot be
            grouped. Both are small, well-scoped backend changes — an audit table and a category column — and they
            unlock every metric on that list at once.
          </p>
          <p className="section-note">
            Queue depth is likewise approximate above. The list endpoint is cursor-paged with no total, so anything past{' '}
            {PROBE} shows as “{PROBE}+”.
          </p>
        </div>
      </section>
    </div>
  );
}
