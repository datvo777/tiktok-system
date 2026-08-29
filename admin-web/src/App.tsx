import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  approve,
  approveAppeal,
  denyAppeal,
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
  const [signedIn, setSignedIn] = useState(false);
  const [status, setStatus] = useState('');

  if (!signedIn) {
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
                setSignedIn(true);
                setStatus('');
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

  return (
    <div className="admin-shell">
      <div className="admin-topbar">
        <div className="admin-brand">
          <span className="admin-brand-mark">A</span>
          Short Video Admin
        </div>
        <button
          className="btn-ghost"
          onClick={async () => {
            await logout();
            setSignedIn(false);
          }}
        >
          Sign out
        </button>
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

  const pending = useQuery({ queryKey: ['pending'], queryFn: listPending, refetchInterval: 5000 });

  const approveMutation = useMutation({
    mutationFn: approve,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending'] }),
  });
  const rejectMutation = useMutation({
    mutationFn: ({ videoId, reason }: { videoId: string; reason: string }) => reject(videoId, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pending'] }),
  });

  const videos: PendingVideo[] = pending.data ?? [];

  return (
    <section className="card">
      <div className="card-head">
        <h2>Pending moderation</h2>
        <span className="count-tag">{videos.length}</span>
      </div>

      {pending.isPending && <p className="card-desc">Loading...</p>}
      {pending.isError && (
        <p className="error-text">
          {(pending.error as Error).message.includes('403') || (pending.error as Error).message.includes('Forbidden')
            ? 'Signed in, but this account does not have the ADMIN role.'
            : `Failed to load: ${(pending.error as Error).message}`}
        </p>
      )}
      {pending.isSuccess && videos.length === 0 && <div className="empty-state">Nothing waiting for a decision.</div>}

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
    </section>
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
