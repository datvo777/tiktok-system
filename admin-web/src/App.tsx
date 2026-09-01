import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Hls from 'hls.js';
import { useEffect, useRef, useState } from 'react';
import {
  approve,
  approveAppeal,
  createModeratorPreviewSession,
  denyAppeal,
  getMe,
  listPending,
  listPendingAppeals,
  login,
  logout,
  quarantine,
  reject,
  removeVideo,
  restore,
  type PendingAppeal,
  type PendingVideo,
} from './api';

export function App() {
  const [email, setEmail] = useState('admin@example.com');
  const [password, setPassword] = useState('correct-horse-battery');
  const [status, setStatus] = useState('');
  const queryClient = useQueryClient();

  // The session cookie survives a page refresh even though React state
  // doesn't -- check it once on load instead of assuming signed-out and
  // forcing a re-login every time the page reloads.
  const me = useQuery({ queryKey: ['me'], queryFn: getMe, retry: false });

  if (me.isPending) {
    return (
      <div className="auth-screen">
        <div className="admin-brand">
          <span className="admin-brand-mark">A</span>
          Short Video Admin
        </div>
      </div>
    );
  }

  if (!me.data) {
    return (
      <div className="auth-screen">
        <div className="admin-brand" style={{ marginBottom: '1.5rem' }}>
          <span className="admin-brand-mark">A</span>
          Short Video Admin
        </div>
        <section className="card">
          <p className="card-desc">
            No self-service admin registration exists; elevate an account's role directly in Postgres
            (<code>UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = ...</code>) for local testing.
          </p>

          <label className="field">
            <span className="field-label">Email</span>
            <input value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className="field">
            <span className="field-label">Password</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>

          <button
            className="btn-primary"
            style={{ marginTop: '1.1rem' }}
            onClick={async () => {
              try {
                setStatus('Signing in...');
                await login(email, password);
                setStatus('');
                await queryClient.invalidateQueries({ queryKey: ['me'] });
              } catch (error) {
                setStatus(`Sign in failed: ${(error as Error).message}`);
              }
            }}
          >
            Sign in
          </button>

          {status && <p className="error-text" style={{ marginTop: '0.75rem' }}>{status}</p>}
        </section>
      </div>
    );
  }

  if (!me.data.roles.includes('ADMIN')) {
    return (
      <div className="auth-screen">
        <div className="admin-brand" style={{ marginBottom: '1.5rem' }}>
          <span className="admin-brand-mark">A</span>
          Short Video Admin
        </div>
        <section className="card">
          <p className="error-text">
            Signed in as {me.data.displayName}, but this account does not have the ADMIN role.
          </p>
          <button
            className="btn-ghost"
            style={{ marginTop: '0.75rem' }}
            onClick={async () => {
              await logout();
              await queryClient.invalidateQueries({ queryKey: ['me'] });
            }}
          >
            Sign out
          </button>
        </section>
      </div>
    );
  }

  return (
    <div className="admin-shell">
      <div className="admin-topbar">
        <div className="admin-brand">
          <span className="admin-brand-mark">A</span>
          Short Video Admin
        </div>
        <div className="btn-row">
          <span className="count-tag">{me.data.displayName}</span>
          <button
            className="btn-ghost"
            onClick={async () => {
              await logout();
              await queryClient.invalidateQueries({ queryKey: ['me'] });
            }}
          >
            Sign out
          </button>
        </div>
      </div>

      <PendingVideos />
      <PendingAppeals />
      <LifecycleActions />
    </div>
  );
}

function PendingVideos() {
  const queryClient = useQueryClient();
  const [reasons, setReasons] = useState<Record<string, string>>({});

  // Keyset pagination only ever hands back a "next" cursor, so Prev/Next
  // navigation is a client-side stack of the cursors already seen: cursorPath[i]
  // is the cursor that fetches page i (cursorPath[0] is undefined, the first
  // page). "Next" grows the stack; "Prev" just moves the index back onto a
  // cursor that's still there.
  const [cursorPath, setCursorPath] = useState<(string | undefined)[]>([undefined]);
  const [pageIndex, setPageIndex] = useState(0);
  const cursor = cursorPath[pageIndex];

  const pending = useQuery({
    queryKey: ['pending', cursor],
    queryFn: () => listPending(cursor),
    refetchInterval: 5000,
  });

  const approveMutation = useMutation({
    mutationFn: approve,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending'] }),
  });
  const rejectMutation = useMutation({
    mutationFn: ({ videoId, reason }: { videoId: string; reason: string }) => reject(videoId, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending'] }),
  });

  const videos: PendingVideo[] = pending.data?.items ?? [];
  const nextCursor = pending.data?.nextCursor ?? null;

  const goNext = () => {
    if (!nextCursor) return;
    setCursorPath((path) => (pageIndex + 1 < path.length ? path : [...path, nextCursor]));
    setPageIndex((i) => i + 1);
  };
  const goPrev = () => setPageIndex((i) => Math.max(0, i - 1));

  return (
    <section className="card">
      <div className="card-head">
        <h2>Pending moderation</h2>
        <span className="count-tag">page {pageIndex + 1}</span>
      </div>

      {pending.isPending && <p className="card-desc">Loading...</p>}
      {pending.isError && (
        <p className="error-text">
          {(pending.error as Error).message.includes('403') || (pending.error as Error).message.includes('Forbidden')
            ? 'Signed in, but this account does not have the ADMIN role.'
            : `Failed to load: ${(pending.error as Error).message}`}
        </p>
      )}
      {pending.isSuccess && videos.length === 0 && pageIndex === 0 && (
        <div className="empty-state">Nothing waiting for a decision.</div>
      )}

      <ul className="item-list">
        {videos.map((video) => (
          <li key={video.videoId} className="item-row">
            <div className="item-meta">
              video <span className="id">{video.videoId}</span>
              <br />
              creator <span className="id">{video.creatorId}</span>
              <br />
              waiting since {video.createdAt}
            </div>
            <ModeratorPreview videoId={video.videoId} />
            <div className="item-actions">
              <button className="btn-primary" onClick={() => approveMutation.mutate(video.videoId)} disabled={approveMutation.isPending}>
                Approve
              </button>
              <input
                placeholder="reason"
                value={reasons[video.videoId] ?? ''}
                onChange={(e) => setReasons((r) => ({ ...r, [video.videoId]: e.target.value }))}
              />
              <button
                className="btn-danger"
                onClick={() => rejectMutation.mutate({ videoId: video.videoId, reason: reasons[video.videoId] ?? '' })}
                disabled={rejectMutation.isPending}
              >
                Reject
              </button>
            </div>
          </li>
        ))}
      </ul>

      {(pageIndex > 0 || nextCursor) && (
        <div className="btn-row" style={{ marginTop: '1rem', justifyContent: 'center' }}>
          <button className="btn-ghost" onClick={goPrev} disabled={pageIndex === 0 || pending.isFetching}>
            ← Prev
          </button>
          <button className="btn-ghost" onClick={goNext} disabled={!nextCursor || pending.isFetching}>
            Next →
          </button>
        </div>
      )}
    </section>
  );
}

/** Admin-only playback so a moderator can actually watch a video before deciding on it. */
function ModeratorPreview({ videoId }: { videoId: string }) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  // One hls.js instance for this component's whole lifetime, reused across
  // repeated Preview clicks via loadSource() instead of destroy()+new Hls().
  // Destroying and immediately re-attaching a *new* MediaSource to the same
  // element is a known race in some browsers -- the old one isn't always
  // fully released before the new attach, which is exactly what
  // "mediaSourceRequiresReset" means. Reusing one instance sidesteps the race
  // entirely instead of trying to win it. Only destroyed when this component
  // itself unmounts (the row leaving the pending-moderation list).
  const hlsRef = useRef<Hls | null>(null);

  useEffect(
    () => () => {
      hlsRef.current?.destroy();
      hlsRef.current = null;
    },
    [],
  );

  const session = useMutation({
    mutationFn: () => createModeratorPreviewSession(videoId),
    onSuccess: (result) => {
      setError(null);
      const url = `/media/videos/${videoId}/${result.processingVersion}/master.m3u8`;
      const el = videoRef.current;
      if (!el) return;
      if (Hls.isSupported()) {
        if (!hlsRef.current) {
          const hls = new Hls();
          hlsRef.current = hls;
          hls.attachMedia(el);
          hls.on(Hls.Events.ERROR, (_event, data) => {
            if (data.fatal) setError(`Playback error: ${data.type} — ${data.details}`);
          });
        }
        hlsRef.current.loadSource(url);
      } else if (el.canPlayType('application/vnd.apple.mpegurl')) {
        el.src = url;
      } else {
        setError('This browser supports neither MSE (hls.js) nor native HLS playback.');
      }
    },
    onError: (err) => setError((err as Error).message),
  });

  return (
    <div style={{ marginTop: '0.5rem' }}>
      <button
        className="btn-ghost"
        onClick={() => {
          if (open) {
            setOpen(false);
          } else {
            setOpen(true);
            session.mutate();
          }
        }}
      >
        {open ? 'Hide preview' : '▶ Preview'}
      </button>
      {/* Always mounted once first opened, so the video element and its hls.js
          instance persist across show/hide instead of tearing down and
          racing a rebuild on every click. */}
      <video
        ref={videoRef}
        controls
        style={{
          display: open ? 'block' : 'none',
          marginTop: '0.5rem',
          maxWidth: '320px',
          borderRadius: 8,
          background: '#000',
        }}
      />
      {open && session.isPending && <p className="item-meta">Requesting preview session...</p>}
      {open && error && <p className="error-text">{error}</p>}
    </div>
  );
}

function PendingAppeals() {
  const queryClient = useQueryClient();
  const [reasons, setReasons] = useState<Record<string, string>>({});

  const pending = useQuery({ queryKey: ['pending-appeals'], queryFn: listPendingAppeals, refetchInterval: 5000 });

  const approveMutation = useMutation({
    mutationFn: ({ videoId, reason }: { videoId: string; reason: string }) => approveAppeal(videoId, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending-appeals'] }),
  });
  const denyMutation = useMutation({
    mutationFn: ({ videoId, reason }: { videoId: string; reason: string }) => denyAppeal(videoId, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending-appeals'] }),
  });

  if (pending.isError) return null; // already surfaced by PendingVideos above

  const appeals: PendingAppeal[] = pending.data ?? [];

  return (
    <section className="card">
      <div className="card-head">
        <h2>Pending appeals</h2>
        <span className="count-tag">{appeals.length}</span>
      </div>

      {pending.isPending && <p className="card-desc">Loading...</p>}
      {pending.isSuccess && appeals.length === 0 && <div className="empty-state">No appeals awaiting review.</div>}

      <ul className="item-list">
        {appeals.map((appeal) => (
          <li key={appeal.videoId} className="item-row">
            <div className="item-meta">
              video <span className="id">{appeal.videoId}</span>
            </div>
            <div className="item-reason">{appeal.reason}</div>
            <div className="item-actions">
              <input
                placeholder="decision reason"
                value={reasons[appeal.videoId] ?? ''}
                onChange={(e) => setReasons((r) => ({ ...r, [appeal.videoId]: e.target.value }))}
              />
              <button
                className="btn-primary"
                onClick={() => approveMutation.mutate({ videoId: appeal.videoId, reason: reasons[appeal.videoId] ?? '' })}
                disabled={approveMutation.isPending}
              >
                Approve appeal
              </button>
              <button
                className="btn-danger"
                onClick={() => denyMutation.mutate({ videoId: appeal.videoId, reason: reasons[appeal.videoId] ?? '' })}
                disabled={denyMutation.isPending}
              >
                Deny
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function LifecycleActions() {
  const [videoId, setVideoId] = useState('');
  const [reason, setReason] = useState('');
  const [status, setStatus] = useState('');

  const run = (action: (id: string, reason: string) => Promise<void>, label: string) => async () => {
    try {
      setStatus(`${label}...`);
      await action(videoId, reason);
      setStatus(`${label} succeeded.`);
    } catch (error) {
      setStatus(`${label} failed: ${(error as Error).message}`);
    }
  };

  return (
    <section className="card">
      <div className="card-head">
        <h2>Video lifecycle actions</h2>
      </div>
      <p className="card-desc">
        Quarantine, restore, or permanently remove a video by id — independent of the moderation decision (brief
        section 18, Milestone 6).
      </p>
      <label className="field">
        <span className="field-label">Video ID</span>
        <input placeholder="videoId" value={videoId} onChange={(e) => setVideoId(e.target.value)} style={{ fontFamily: 'var(--font-mono)' }} />
      </label>
      <label className="field">
        <span className="field-label">Reason (quarantine / remove only)</span>
        <input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <div className="btn-row" style={{ marginTop: '0.9rem' }}>
        <button onClick={run(quarantine, 'Quarantine')} disabled={!videoId}>
          Quarantine
        </button>
        <button onClick={run(() => restore(videoId), 'Restore')} disabled={!videoId}>
          Restore
        </button>
        <button className="btn-danger" onClick={run(removeVideo, 'Remove')} disabled={!videoId}>
          Remove
        </button>
      </div>
      {status && <p className="item-meta" style={{ marginTop: '0.75rem' }}>{status}</p>}
    </section>
  );
}
