import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { search, type SearchHit } from './api';

/** Search API (brief section 20, Milestone 7): matches by creator display name. */
export function SearchPanel() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[] | null>(null);

  const run = useMutation({
    mutationFn: () => search(query),
    onSuccess: (response) => setResults(response.results),
  });

  return (
    <section className="card">
      <div className="card-head">
        <h2>Search</h2>
        <span className="card-eyebrow">Milestone 7</span>
      </div>
      <p className="card-desc">Finds published videos by creator name.</p>

      <div className="search-bar">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by creator name..."
          onKeyDown={(e) => e.key === 'Enter' && query.trim() && run.mutate()}
        />
        <button className="btn-primary" disabled={!query.trim() || run.isPending} onClick={() => run.mutate()}>
          {run.isPending ? '…' : 'Search'}
        </button>
      </div>

      {run.isError && (
        <div className="callout" style={{ background: 'var(--color-danger-soft)' }}>
          {(run.error as Error).message}
        </div>
      )}

      {results && (
        <ul className="search-results" data-testid="search-results">
          {results.length === 0 && <li className="feed-empty">No matches.</li>}
          {results.map((hit) => (
            <li key={hit.videoId} data-testid={`search-hit-${hit.videoId}`} className="search-hit">
              <span className="search-hit-avatar">{hit.creatorDisplayName.slice(0, 1).toUpperCase() || '?'}</span>
              <div>
                <div className="search-hit-name">{hit.creatorDisplayName}</div>
                <div className="search-hit-video">{hit.videoId}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
