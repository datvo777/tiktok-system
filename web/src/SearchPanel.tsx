import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { createPublicSession, search, type SearchHit } from './api';
import { Sheet } from './App';
import { SearchIcon } from './icons';
import { attachHls, detachHls } from './Upload';
import { Avatar, handleFor } from './ui';

/** Search API (brief section 20, Milestone 7): matches by creator display name, video title, or description. */
export function SearchPanel() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[] | null>(null);
  const [openHit, setOpenHit] = useState<SearchHit | null>(null);

  const run = useMutation({
    mutationFn: () => search(query),
    onSuccess: (response) => setResults(response.results),
  });

  return (
    <div>
      <div className="search-bar">
        <SearchIcon />
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search creators or videos"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && query.trim() && !run.isPending) run.mutate();
          }}
        />
        <button className="btn-primary" disabled={!query.trim() || run.isPending} onClick={() => run.mutate()}>
          {run.isPending ? '…' : 'Search'}
        </button>
      </div>

      {run.isError && <div className="status-line is-error">{(run.error as Error).message}</div>}

      {results && results.length === 0 && (
        <div className="empty">
          <SearchIcon />
          <span className="empty-text">No creators or videos match “{query}”.</span>
        </div>
      )}

      {results && results.length > 0 && (
        <ul className="search-results" data-testid="search-results">
          {results.map((hit) => (
            <li key={hit.videoId}>
              <button
                type="button"
                data-testid={`search-hit-${hit.videoId}`}
                className="search-hit"
                onClick={() => setOpenHit(hit)}
              >
                <Avatar seed={hit.creatorId} label={hit.creatorDisplayName} size="sm" />
                <div style={{ minWidth: 0 }}>
                  <div className="search-hit-name">{hit.title || hit.creatorDisplayName}</div>
                  <div className="search-hit-sub">
                    {hit.creatorDisplayName} · {handleFor(hit.creatorId)}
                  </div>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}

      {openHit && (
        <Sheet title={openHit.title || 'Video'} onClose={() => setOpenHit(null)}>
          <SearchHitPlayer hit={openHit} />
        </Sheet>
      )}
    </div>
  );
}

function SearchHitPlayer({ hit }: { hit: SearchHit }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [orientation, setOrientation] = useState<'portrait' | 'landscape' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const session = useMutation({
    mutationFn: () => createPublicSession(hit.videoId),
    onSuccess: (result) => {
      setError(null);
      attachHls(videoRef.current, hit.videoId, result.processingVersion, setError);
      videoRef.current?.play().catch(() => {});
    },
    onError: (err) => setError((err as Error).message),
  });

  // Mirrors Upload's Preview: fetch a session and attach as soon as the sheet opens,
  // rather than making the viewer press play a second time after already clicking in.
  useEffect(() => {
    session.mutate();
    return () => detachHls(videoRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hit.videoId]);

  return (
    <div>
      <video
        ref={videoRef}
        controls
        autoPlay
        className={`preview-video${orientation === 'landscape' ? ' preview-video-landscape' : ''}`}
        style={{ margin: '0 auto' }}
        onLoadedMetadata={(e) => {
          const { videoWidth, videoHeight } = e.currentTarget;
          if (videoWidth && videoHeight) setOrientation(videoWidth >= videoHeight ? 'landscape' : 'portrait');
        }}
      />
      <div className="upload-meta" style={{ marginTop: '0.85rem' }}>
        <Avatar seed={hit.creatorId} label={hit.creatorDisplayName} size="sm" />
        <div>
          <div className="search-hit-name">{hit.creatorDisplayName}</div>
          <div className="search-hit-sub">{handleFor(hit.creatorId)}</div>
        </div>
      </div>
      {hit.description && <p className="search-hit-sub" style={{ marginTop: '0.6rem' }}>{hit.description}</p>}
      {error && <div className="status-line is-error">{error}</div>}
    </div>
  );
}
