import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { search, type SearchHit } from './api';
import { SearchIcon } from './icons';
import { Avatar, handleFor } from './ui';

/** Search API (brief section 20, Milestone 7): matches by creator display name, video title, or description. */
export function SearchPanel() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[] | null>(null);

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
            <li key={hit.videoId} data-testid={`search-hit-${hit.videoId}`} className="search-hit">
              <Avatar seed={hit.creatorId} label={hit.creatorDisplayName} size="sm" />
              <div style={{ minWidth: 0 }}>
                <div className="search-hit-name">{hit.title || hit.creatorDisplayName}</div>
                <div className="search-hit-sub">
                  {hit.creatorDisplayName} · {handleFor(hit.creatorId)}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
