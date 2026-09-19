import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { approve, getVideoDetail, listPending, listPendingAppeals, reject, type PendingVideo } from './api';
import { AppealsQueue } from './Appeals';
import { ageSince, EntityId, ModeratorPlayer, StateChip, useToast } from './ui';

/**
 * Mirrors the server's `PolicyCategory` enum, which is now the authoritative
 * classification — the slug is sent as its own field and validated server-side,
 * so an unknown value here is rejected rather than silently stored.
 *
 * The tier is shown but never sent: severity is a property of the policy, and
 * the server derives it. Sending it would let two clients disagree about how
 * severe the same category is.
 */
export const POLICIES = [
  { slug: 'MINOR_SAFETY', label: 'Minor safety', tier: 'P0' },
  { slug: 'SELF_HARM', label: 'Self-harm', tier: 'P0' },
  { slug: 'VIOLENCE_GORE', label: 'Violence / gore', tier: 'P1' },
  { slug: 'ADULT_CONTENT', label: 'Adult content', tier: 'P1' },
  { slug: 'HARASSMENT', label: 'Harassment', tier: 'P1' },
  { slug: 'SPAM_DECEPTIVE', label: 'Spam / deceptive', tier: 'P2' },
  { slug: 'IP_VIOLATION', label: 'Intellectual property', tier: 'P2' },
] as const;

/** How long a decision stays reversible before it is actually sent. */
const UNDO_MS = 5000;

const WORKING_SET = 50;

type Deferred = {
  id: number;
  video: PendingVideo;
  action: 'approve' | 'reject';
  policyCategory: string | null;
  reason: string;
  summary: string;
  expiresAt: number;
};

/**
 * Review is the landing surface, and it holds every queue of pending decisions.
 * Moderation and appeals are different inputs to the same activity, so they are
 * queues within one surface rather than two unrelated sections of a scroll.
 */
export function Review() {
  const [queue, setQueue] = useState<'moderation' | 'appeals'>('moderation');

  // Only for the tab count — the appeals queue fetches its own data. Shares a
  // cache key with AppealsQueue so this costs nothing extra.
  const appeals = useQuery({
    queryKey: ['pending-appeals'],
    queryFn: listPendingAppeals,
    refetchInterval: 30000,
  });

  const appealCount = appeals.data?.length ?? 0;

  return (
    <div className="review-shell">
      <div className="queue-switch" role="tablist" aria-label="Review queues">
        <button
          role="tab"
          aria-selected={queue === 'moderation'}
          className={queue === 'moderation' ? 'is-on' : ''}
          onClick={() => setQueue('moderation')}
        >
          Moderation
        </button>
        <button
          role="tab"
          aria-selected={queue === 'appeals'}
          className={queue === 'appeals' ? 'is-on' : ''}
          onClick={() => setQueue('appeals')}
        >
          Appeals
          {appealCount > 0 && <span className="badge is-warn">{appealCount}</span>}
        </button>
      </div>

      {queue === 'moderation' ? <ModerationQueue /> : <AppealsQueue />}
    </div>
  );
}

function ModerationQueue() {
  const queryClient = useQueryClient();
  const pushToast = useToast();

  const [resolved, setResolved] = useState<Set<string>>(() => new Set());
  const [skipped, setSkipped] = useState<string[]>([]);
  const [deferred, setDeferred] = useState<Deferred[]>([]);
  const [rejectArmed, setRejectArmed] = useState(false);
  const [note, setNote] = useState('');
  const [decidedCount, setDecidedCount] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  const timers = useRef(new Map<number, number>());
  const nextId = useRef(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  const queue = useQuery({
    queryKey: ['review-queue'],
    queryFn: () => listPending(undefined, WORKING_SET),
    refetchInterval: 15000,
  });

  const items = useMemo(() => queue.data?.items ?? [], [queue.data]);

  // Everything not yet acted on, in server (oldest-first) order.
  const live = useMemo(() => items.filter((i) => !resolved.has(i.videoId)), [items, resolved]);
  // Skipped items are deferred to the back rather than dropped — a reviewer who
  // skips is saying "not now", not "never".
  const current = useMemo(
    () => live.find((i) => !skipped.includes(i.videoId)) ?? live.find((i) => skipped.includes(i.videoId)) ?? null,
    [live, skipped],
  );

  const detail = useQuery({
    queryKey: ['video-detail', current?.videoId],
    queryFn: () => getVideoDetail(current!.videoId),
    enabled: current !== null,
    retry: false,
  });

  /* ---------------------------------------------------------- committing -- */

  // Mirrors `deferred` so the unmount flush can read it without re-subscribing.
  const deferredRef = useRef<Deferred[]>([]);
  useEffect(() => {
    deferredRef.current = deferred;
  }, [deferred]);

  const send = useCallback(
    async (d: Deferred) => {
      try {
        if (d.action === 'approve') await approve(d.video.videoId);
        else await reject(d.video.videoId, d.policyCategory!, d.reason);
        setDecidedCount((n) => n + 1);
        void queryClient.invalidateQueries({ queryKey: ['review-queue'] });
        void queryClient.invalidateQueries({ queryKey: ['queue-health'] });
      } catch (error) {
        // The optimistic removal has to be rolled back or the item silently
        // vanishes from the queue without ever having been decided.
        setResolved((prev) => {
          const next = new Set(prev);
          next.delete(d.video.videoId);
          return next;
        });
        pushToast('error', `${d.summary} failed: ${(error as Error).message}`);
      }
    },
    [queryClient, pushToast],
  );

  const commit = useCallback(
    (d: Deferred) => {
      timers.current.delete(d.id);
      setDeferred((prev) => prev.filter((x) => x.id !== d.id));
      void send(d);
    },
    [send],
  );

  // Anything still in its undo window when this surface goes away is sent
  // immediately — a decision the reviewer made must not be lost to navigation.
  useEffect(
    () => () => {
      for (const handle of timers.current.values()) clearTimeout(handle);
      timers.current.clear();
      for (const d of deferredRef.current) void send(d);
    },
    [send],
  );

  const decide = useCallback(
    (action: 'approve' | 'reject', policy?: (typeof POLICIES)[number]) => {
      if (!current) return;
      if (action === 'reject' && !policy) return;

      const d: Deferred = {
        id: ++nextId.current,
        video: current,
        action,
        policyCategory: policy ? policy.slug : null,
        reason: note.trim().slice(0, 200),
        summary: policy ? `Rejected · ${policy.label}` : 'Approved',
        expiresAt: Date.now() + UNDO_MS,
      };

      // Resolved immediately so the queue advances now; the request goes later.
      setResolved((prev) => new Set(prev).add(current.videoId));
      setDeferred((prev) => [...prev, d]);
      setRejectArmed(false);
      setNote('');

      const handle = window.setTimeout(() => commit(d), UNDO_MS);
      timers.current.set(d.id, handle);
    },
    [current, note, commit],
  );

  const undo = useCallback((id: number) => {
    const handle = timers.current.get(id);
    if (handle !== undefined) clearTimeout(handle);
    timers.current.delete(id);
    setDeferred((prev) => {
      const target = prev.find((d) => d.id === id);
      if (target) {
        setResolved((r) => {
          const next = new Set(r);
          next.delete(target.video.videoId);
          return next;
        });
      }
      return prev.filter((d) => d.id !== id);
    });
  }, []);

  const skip = useCallback(() => {
    if (!current) return;
    setSkipped((prev) => (prev.includes(current.videoId) ? prev : [...prev, current.videoId]));
    setRejectArmed(false);
  }, [current]);

  /* ------------------------------------------------------------ countdown -- */

  useEffect(() => {
    if (deferred.length === 0) return;
    const handle = window.setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(handle);
  }, [deferred.length]);

  /* ------------------------------------------------------------- keyboard -- */

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;

      const key = e.key.toLowerCase();

      if (key === 'u') {
        const latest = deferred[deferred.length - 1];
        if (latest) {
          e.preventDefault();
          undo(latest.id);
        }
        return;
      }

      if (!current) return;

      if (key === 'escape') {
        setRejectArmed(false);
        return;
      }

      // Rejection is two keystrokes by design: R arms, then a digit picks the
      // policy and commits. Policy is therefore mandatory by construction —
      // there is no path to a rejection without one.
      if (rejectArmed && /^[1-9]$/.test(key)) {
        const policy = POLICIES[Number(key) - 1];
        if (policy) {
          e.preventDefault();
          decide('reject', policy);
        }
        return;
      }

      if (key === 'a') {
        e.preventDefault();
        decide('approve');
      } else if (key === 'r') {
        e.preventDefault();
        setRejectArmed((v) => !v);
      } else if (key === 's') {
        e.preventDefault();
        skip();
      } else if (key === ' ') {
        e.preventDefault();
        const el = videoRef.current;
        if (el) void (el.paused ? el.play().catch(() => {}) : el.pause());
      } else if (key === 'm') {
        e.preventDefault();
        const el = videoRef.current;
        if (el) el.muted = !el.muted;
      }
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [current, rejectArmed, deferred, decide, skip, undo]);

  /* ---------------------------------------------------------------- view -- */

  const position = current ? items.findIndex((i) => i.videoId === current.videoId) + 1 : 0;
  const oldest = live[0];

  return (
    <div className="review">
      <div className="review-main">
        <div className="review-head">
          <div className="qpos">
            QUEUE <b>moderation</b>
            {current && (
              <>
                {' '}
                · item <b>{position}</b> of <b>{items.length}{queue.data?.nextCursor ? '+' : ''}</b>
              </>
            )}
          </div>
          <div className="qstats">
            <div className={`qstat${oldest && Date.now() - new Date(oldest.createdAt).getTime() > 3600000 ? ' is-warn' : ''}`}>
              Oldest<b>{oldest ? ageSince(oldest.createdAt) : '—'}</b>
            </div>
            <div className="qstat">
              Waiting<b>{live.length}{queue.data?.nextCursor ? '+' : ''}</b>
            </div>
            <div className="qstat">
              This session<b>{decidedCount}</b>
            </div>
          </div>
        </div>

        {queue.isPending && <div className="stage"><div className="stage-empty">Loading queue…</div></div>}

        {queue.isError && (
          <div className="stage">
            <div className="stage-empty">
              <h3>Could not load the queue</h3>
              <p className="error-text">{(queue.error as Error).message}</p>
            </div>
          </div>
        )}

        {queue.isSuccess && !current && (
          <div className="stage">
            <div className="stage-empty">
              <h3>Queue clear</h3>
              <p>Nothing is waiting for a decision. New uploads appear here automatically.</p>
            </div>
          </div>
        )}

        {current && (
          <>
            <div className="stage">
              <ModeratorPlayer videoId={current.videoId} videoRef={videoRef} autoPlay />
            </div>

            <div className="player-tools">
              <span className="hint"><span className="kbd">Space</span> play</span>
              <span className="hint"><span className="kbd">M</span> sound</span>
              <span className="hint" style={{ marginLeft: 'auto' }}>
                Starts muted — browsers refuse autoplay with sound
              </span>
            </div>

            <div className="context">
              <div className="ctx">
                <span className="k">Title</span>
                <span className={`v${detail.data?.title ? '' : ' is-dim'}`}>
                  {detail.data?.title || (detail.isPending ? '…' : 'Untitled')}
                </span>
              </div>
              <div className="ctx">
                <span className="k">Creator</span>
                <span className={`v${detail.data?.creatorDisplayName ? '' : ' is-dim'}`}>
                  {detail.data?.creatorDisplayName || (detail.isPending ? '…' : 'Unknown')}
                </span>
              </div>
              <div className="ctx">
                <span className="k">Waiting</span>
                <span className={`v${Date.now() - new Date(current.createdAt).getTime() > 3600000 ? ' is-warn' : ''}`}>
                  {ageSince(current.createdAt)}
                </span>
              </div>
              <div className="ctx">
                <span className="k">Video</span>
                <span className="v"><EntityId value={current.videoId} /></span>
              </div>
              <div className="ctx">
                <span className="k">Creator id</span>
                <span className="v"><EntityId value={current.creatorId} /></span>
              </div>
            </div>
          </>
        )}
      </div>

      <div className="rail">
        <div className="rail-sec">
          <span className="rail-label">Decision</span>
          <div className="decide">
            <button className="btn-ok" disabled={!current} onClick={() => decide('approve')}>
              Approve <span className="kbd">A</span>
            </button>
            <button
              className="btn-crit"
              disabled={!current}
              aria-pressed={rejectArmed}
              onClick={() => setRejectArmed((v) => !v)}
            >
              Reject <span className="kbd">R</span>
            </button>
            <button disabled={!current} onClick={skip}>
              Skip <span className="kbd">S</span>
            </button>
            <button disabled={deferred.length === 0} onClick={() => undo(deferred[deferred.length - 1]!.id)}>
              Undo <span className="kbd">U</span>
            </button>
          </div>
        </div>

        <div className="rail-sec">
          <span className="rail-label">
            <span>Policy — required to reject</span>
            {rejectArmed && <span style={{ color: 'var(--crit)' }}>pick one</span>}
          </span>
          <div className={`policy-list${rejectArmed ? ' is-armed' : ''}`}>
            {POLICIES.map((policy, i) => (
              <button
                key={policy.slug}
                className="policy"
                disabled={!current}
                onClick={() => decide('reject', policy)}
                title={`Reject as ${policy.label}`}
              >
                <span className="num">{i + 1}</span>
                <span className="label">{policy.label}</span>
                <span className={`tier tier-${policy.tier}`}>{policy.tier}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="rail-sec">
          <label className="field">
            <span className="field-label">Note (optional, kept with the policy)</span>
            <input
              value={note}
              maxLength={140}
              placeholder="Extra detail for the record"
              onChange={(e) => setNote(e.target.value)}
            />
          </label>
        </div>

        {detail.data && (
          <div className="rail-sec">
            <span className="rail-label">Current state</span>
            <div className="chip-row">
              <StateChip state={detail.data.processingState} />
              <StateChip state={detail.data.moderationState} />
              <StateChip state={detail.data.publicationState} />
              <StateChip state={detail.data.assetLifecycleState} />
              <StateChip state={detail.data.legalServingState} />
            </div>
          </div>
        )}

        {deferred.length > 0 && (
          <div className="rail-sec">
            <span className="rail-label">Reversible</span>
            {deferred.map((d) => (
              <div key={d.id} className="undo">
                <span className="grow">{d.summary}</span>
                <span className="count">{Math.max(0, Math.ceil((d.expiresAt - now) / 1000))}s</span>
                <button onClick={() => undo(d.id)}>Undo</button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
