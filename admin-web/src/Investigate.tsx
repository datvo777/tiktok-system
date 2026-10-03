import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  approve,
  getVideoDetail,
  quarantine,
  reinstateAccount,
  reject,
  removeVideo,
  reprocessVideo,
  restore,
  searchAccounts,
  searchVideos,
  suspendAccount,
  type AdminAccount,
  type VideoSearchHit,
} from './api';
import { POLICIES } from './Review';
import { ageSince, EntityId, ModeratorPlayer, StateChip, useToast } from './ui';

export function Investigate() {
  return (
    <div className="page">
      <VideoInvestigation />
      <AccountInvestigation />
    </div>
  );
}

/* ============================================================== videos == */

function VideoInvestigation() {
  const queryClient = useQueryClient();
  const pushToast = useToast();

  const [query, setQuery] = useState('');
  const [results, setResults] = useState<VideoSearchHit[] | null>(null);
  const [videoId, setVideoId] = useState('');
  const [policy, setPolicy] = useState<string>(POLICIES[5].slug);
  const [note, setNote] = useState('');
  const [target, setTarget] = useState<string | null>(null);

  const search = useMutation({
    mutationFn: () => searchVideos(query.trim()),
    onSuccess: setResults,
    onError: (error) => pushToast('error', `Search failed: ${(error as Error).message}`),
  });

  const detail = useQuery({
    queryKey: ['video-detail', target],
    queryFn: () => getVideoDetail(target!),
    enabled: target !== null,
    retry: false,
  });

  function open(id: string) {
    setVideoId(id);
    setTarget(id);
  }

  function act(label: string, run: () => Promise<void>) {
    return async () => {
      try {
        await run();
        pushToast('success', `${label} succeeded.`);
        void queryClient.invalidateQueries({ queryKey: ['video-detail', target] });
        void queryClient.invalidateQueries({ queryKey: ['review-queue'] });
        void queryClient.invalidateQueries({ queryKey: ['queue-health'] });
      } catch (error) {
        pushToast('error', `${label} failed: ${(error as Error).message}`);
      }
    };
  }

  const trimmedNote = note.trim().slice(0, 200);

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Videos</h2>
          <span className="sub">Search published videos, or open any video by id</span>
        </div>
      </div>

      <div className="panel-body">
        <div className="btn-row">
          <input
            placeholder="Search by creator, title or description…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && query.trim() && !search.isPending) search.mutate();
            }}
          />
          <button onClick={() => search.mutate()} disabled={!query.trim() || search.isPending}>
            {search.isPending ? 'Searching…' : 'Search'}
          </button>
        </div>

        <p className="section-note">
          Search only covers published videos — it reads the same index the public app uses. A video still awaiting
          moderation will not appear here, but opening it by id below works regardless of state.
        </p>

        {results && results.length === 0 && <div className="empty">No published videos match “{query}”.</div>}

        {results && results.length > 0 && (
          <div className="rows">
            {results.map((hit) => (
              <div key={hit.videoId} className="row">
                <div className="row-head">
                  <span className="row-title">{hit.title || 'Untitled'}</span>
                  <span className="row-sub">{hit.creatorDisplayName}</span>
                </div>
                <div className="row-actions">
                  <EntityId label="video" value={hit.videoId} />
                  <button className="btn-quiet" style={{ marginLeft: 'auto' }} onClick={() => open(hit.videoId)}>
                    Open
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="btn-row">
          <input
            className="mono"
            placeholder="Or paste a video id"
            value={videoId}
            onChange={(e) => setVideoId(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && videoId.trim()) setTarget(videoId.trim());
            }}
          />
          <button onClick={() => setTarget(videoId.trim())} disabled={!videoId.trim() || detail.isFetching}>
            {detail.isFetching ? 'Opening…' : 'Open'}
          </button>
        </div>

        {detail.isError && <p className="error-text">{(detail.error as Error).message}</p>}

        {detail.data && (
          <div className="row">
            <div className="row-head">
              <span className="row-title">{detail.data.title || 'Untitled'}</span>
              <span className="row-sub">
                {detail.data.creatorDisplayName || 'Unknown creator'} · updated {ageSince(detail.data.updatedAt)} ago
              </span>
            </div>

            <div className="btn-row">
              <EntityId label="video" value={detail.data.videoId} />
              <EntityId label="creator" value={detail.data.creatorId} />
            </div>

            <div className="chip-row">
              <StateChip state={detail.data.processingState} />
              <StateChip state={detail.data.moderationState} />
              <StateChip state={detail.data.publicationState} />
              <StateChip state={detail.data.assetLifecycleState} />
              <StateChip state={detail.data.legalServingState} />
              {detail.data.isVideoEligible && <span className="chip chip-ok">servable</span>}
            </div>

            <div style={{ display: 'flex', justifyContent: 'center', padding: '0.5rem 0' }}>
              <ModeratorPlayer videoId={detail.data.videoId} />
            </div>

            <div className="btn-row">
              <label className="field" style={{ flex: '1 1 12rem' }}>
                <span className="field-label">Policy — used when rejecting</span>
                <select value={policy} onChange={(e) => setPolicy(e.target.value)}>
                  {POLICIES.map((p) => (
                    <option key={p.slug} value={p.slug}>
                      {p.tier} · {p.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field" style={{ flex: '2 1 14rem' }}>
                <span className="field-label">Note — added to reject / quarantine / remove</span>
                <input value={note} maxLength={140} placeholder="Optional detail" onChange={(e) => setNote(e.target.value)} />
              </label>
            </div>

            <div className="btn-row">
              <button className="btn-ok" onClick={act('Approve', () => approve(detail.data!.videoId))}>
                Approve
              </button>
              <button
                className="btn-crit"
                onClick={act('Reject', () => reject(detail.data!.videoId, policy, trimmedNote))}
              >
                Reject
              </button>
              <button onClick={act('Quarantine', () => quarantine(detail.data!.videoId, trimmedNote))}>
                Quarantine
              </button>
              <button onClick={act('Restore', () => restore(detail.data!.videoId))}>Restore</button>
              <button onClick={act('Reprocess', () => reprocessVideo(detail.data!.videoId))}>Reprocess</button>
              <button className="btn-crit" onClick={act('Remove', () => removeVideo(detail.data!.videoId, trimmedNote))}>
                Remove
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

/* ============================================================ accounts == */

function AccountInvestigation() {
  const pushToast = useToast();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<AdminAccount[] | null>(null);
  const [reasons, setReasons] = useState<Record<string, string>>({});

  const search = useMutation({
    mutationFn: () => searchAccounts(query.trim()),
    onSuccess: setResults,
    onError: (error) => pushToast('error', `Search failed: ${(error as Error).message}`),
  });

  const suspendMutation = useMutation({
    mutationFn: ({ accountId, reason }: { accountId: string; reason: string }) => suspendAccount(accountId, reason),
    onSuccess: () => {
      pushToast('success', 'Account suspended.');
      search.mutate();
    },
    onError: (error) => pushToast('error', `Suspend failed: ${(error as Error).message}`),
  });

  const reinstateMutation = useMutation({
    mutationFn: ({ accountId, reason }: { accountId: string; reason: string }) => reinstateAccount(accountId, reason),
    onSuccess: () => {
      pushToast('success', 'Account reinstated.');
      search.mutate();
    },
    onError: (error) => pushToast('error', `Reinstate failed: ${(error as Error).message}`),
  });

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Accounts</h2>
          <span className="sub">Suspending a creator blocks every video they have</span>
        </div>
      </div>

      <div className="panel-body">
        <div className="btn-row">
          <input
            placeholder="Search by email…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && query.trim() && !search.isPending) search.mutate();
            }}
          />
          <button onClick={() => search.mutate()} disabled={!query.trim() || search.isPending}>
            {search.isPending ? 'Searching…' : 'Search'}
          </button>
        </div>

        {results && results.length === 0 && <div className="empty">No accounts match “{query}”.</div>}

        {results && results.length > 0 && (
          <div className="rows">
            {results.map((account) => (
              <div key={account.accountId} className="row">
                <div className="row-head">
                  <span className="row-title">{account.displayName}</span>
                  <span className="row-sub">{account.email}</span>
                </div>

                <div className="btn-row">
                  <EntityId label="account" value={account.accountId} />
                  <StateChip state={account.state} />
                  {account.roles.map((role) => (
                    <span key={role} className="chip">
                      {role}
                    </span>
                  ))}
                  <span className="row-sub">joined {ageSince(account.createdAt)} ago</span>
                </div>

                <div className="row-actions">
                  <input
                    placeholder="Reason"
                    value={reasons[account.accountId] ?? ''}
                    onChange={(e) => setReasons((r) => ({ ...r, [account.accountId]: e.target.value }))}
                  />
                  <button
                    className="btn-crit"
                    disabled={account.state === 'SUSPENDED' || suspendMutation.isPending}
                    onClick={() =>
                      suspendMutation.mutate({ accountId: account.accountId, reason: reasons[account.accountId] ?? '' })
                    }
                  >
                    Suspend
                  </button>
                  <button
                    disabled={account.state === 'ACTIVE' || reinstateMutation.isPending}
                    onClick={() =>
                      reinstateMutation.mutate({ accountId: account.accountId, reason: reasons[account.accountId] ?? '' })
                    }
                  >
                    Reinstate
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
