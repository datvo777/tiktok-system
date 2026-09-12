import { keepPreviousData, useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { createPublicSession, getVideoCounts, posterUrl, search, type SearchHit } from './api';
import { Sheet } from './App';
import { ChevronRightIcon, CloseIcon, CommentIcon, HeartIcon, PlayIcon, SearchIcon } from './icons';
import { attachHls, detachHls } from './Upload';
import { Avatar, avatarHue, formatHandle, relativeTime } from './ui';

type Tab = 'all' | 'videos' | 'creators';

/** One creator, folded out of the video hits they appear in. */
type CreatorHit = {
  creatorId: string;
  displayName: string;
  /** Empty for creators whose videos were indexed before handles existed. */
  handle: string;
  videoCount: number;
};

const RECENT_KEY = 'search:recent';
const RECENT_MAX = 6;
const DEBOUNCE_MS = 280;

function readRecents(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(raw) ? raw.filter((v): v is string => typeof v === 'string').slice(0, RECENT_MAX) : [];
  } catch {
    // A corrupt or unavailable store is not worth failing the panel over.
    return [];
  }
}

function writeRecents(next: string[]) {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next.slice(0, RECENT_MAX)));
  } catch {
    /* private mode / quota -- recents are a convenience, not state we need. */
  }
}

/**
 * Search API (brief section 20, Milestone 7): matches by creator display name,
 * video title, or description.
 *
 * The server returns a flat list of video hits, but a query like a creator's
 * name is really a search *for that creator*, and their videos all came back
 * looking like near-identical rows. So the panel folds the hits into a creator
 * section plus a video section, and lets a creator row narrow the videos to
 * just theirs. Typing searches on its own (debounced) -- pressing a button to
 * see results is a round trip the viewer shouldn't have to make.
 */
export function SearchPanel() {
  const [query, setQuery] = useState('');
  const [term, setTerm] = useState('');
  const [tab, setTab] = useState<Tab>('all');
  const [creatorFilter, setCreatorFilter] = useState<CreatorHit | null>(null);
  const [active, setActive] = useState(0);
  const [openHit, setOpenHit] = useState<SearchHit | null>(null);
  const [recents, setRecents] = useState<string[]>(readRecents);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // Debounce the field into the term the query actually keys on, so a fast
  // typist costs one request instead of one per character.
  useEffect(() => {
    const trimmed = query.trim();
    if (trimmed === term) return;
    const timer = setTimeout(() => setTerm(trimmed), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query, term]);

  const results = useInfiniteQuery({
    queryKey: ['search', term],
    queryFn: ({ pageParam }: { pageParam: number }) => search(term, pageParam),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.page + 1 : undefined),
    enabled: term.length > 0,
    // Keep the previous page of hits on screen while the next term loads, so
    // refining a query doesn't flash the list back to empty on every keystroke.
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    retry: false,
  });

  const hits = useMemo(() => results.data?.pages.flatMap((p) => p.results) ?? [], [results.data]);

  const creators = useMemo(() => {
    const byId = new Map<string, CreatorHit>();
    for (const hit of hits) {
      const existing = byId.get(hit.creatorId);
      if (existing) existing.videoCount += 1;
      else
        byId.set(hit.creatorId, {
          creatorId: hit.creatorId,
          displayName: hit.creatorDisplayName,
          handle: hit.creatorHandle,
          videoCount: 1,
        });
    }
    return [...byId.values()].sort((a, b) => b.videoCount - a.videoCount);
  }, [hits]);

  const videos = useMemo(
    () => (creatorFilter ? hits.filter((hit) => hit.creatorId === creatorFilter.creatorId) : hits),
    [hits, creatorFilter],
  );

  const showCreators = tab !== 'videos' && !creatorFilter && creators.length > 0;
  const showVideos = tab !== 'creators' && videos.length > 0;

  // One flat list of what is on screen, so ArrowUp/ArrowDown can walk the
  // results without the viewer's hands leaving the field.
  const rows = useMemo(
    () => [
      ...(showCreators ? creators.map((c) => ({ kind: 'creator' as const, creator: c })) : []),
      ...(showVideos ? videos.map((v) => ({ kind: 'video' as const, hit: v })) : []),
    ],
    [showCreators, showVideos, creators, videos],
  );

  useEffect(() => setActive(0), [term, tab, creatorFilter]);
  useEffect(() => setCreatorFilter(null), [term]);

  // Follow the keyboard cursor when it walks past the fold.
  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  function remember(value: string) {
    const trimmed = value.trim();
    if (!trimmed) return;
    const next = [trimmed, ...recents.filter((r) => r.toLowerCase() !== trimmed.toLowerCase())].slice(0, RECENT_MAX);
    setRecents(next);
    writeRecents(next);
  }

  function runNow(value: string) {
    setQuery(value);
    setTerm(value.trim());
    remember(value);
    inputRef.current?.focus();
  }

  function openRow(index: number) {
    const row = rows[index];
    if (!row) return;
    remember(term);
    if (row.kind === 'creator') {
      setCreatorFilter(row.creator);
      setTab('videos');
    } else {
      setOpenHit(row.hit);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown' && rows.length) {
      e.preventDefault();
      setActive((i) => (i + 1) % rows.length);
    } else if (e.key === 'ArrowUp' && rows.length) {
      e.preventDefault();
      setActive((i) => (i - 1 + rows.length) % rows.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (rows.length) openRow(active);
      else runNow(query);
    } else if (e.key === 'Escape' && query) {
      // Clear the field first; a second Escape falls through to closing the sheet.
      e.stopPropagation();
      clearQuery();
    }
  }

  function clearQuery() {
    setQuery('');
    setTerm('');
    setCreatorFilter(null);
    inputRef.current?.focus();
  }

  const searching = results.isFetching;
  const settled = term.length > 0 && !results.isPending;

  return (
    <div className="search-panel">
      <div className="search-sticky">
        <div className="search-bar">
          <SearchIcon />
          <input
            ref={inputRef}
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Search creators or videos"
            aria-label="Search creators or videos"
            role="combobox"
            aria-expanded={rows.length > 0}
            aria-controls="search-results"
          />
          {searching && <span className="search-spinner" aria-label="Searching" />}
          {query && !searching && (
            <button type="button" className="search-clear" onClick={clearQuery} aria-label="Clear search">
              <CloseIcon size={16} />
            </button>
          )}
        </div>

        {settled && hits.length > 0 && (
          <div className="search-filters" role="tablist" aria-label="Result type">
            <FilterTab id="all" tab={tab} onSelect={setTab} label="All" count={creators.length + hits.length} />
            <FilterTab id="videos" tab={tab} onSelect={setTab} label="Videos" count={hits.length} />
            <FilterTab id="creators" tab={tab} onSelect={setTab} label="Creators" count={creators.length} />
          </div>
        )}

        {creatorFilter && (
          <button type="button" className="search-chip-filter" onClick={() => setCreatorFilter(null)}>
            <Avatar seed={creatorFilter.creatorId} label={creatorFilter.displayName} size="sm" />
            <span>{creatorFilter.displayName}</span>
            <CloseIcon size={14} />
          </button>
        )}
      </div>

      {results.isError && <div className="status-line is-error">{(results.error as Error).message}</div>}

      {/* Nothing typed yet: recents beat a blank rectangle, and the hint says
          what is actually searchable. */}
      {term.length === 0 && (
        <div className="search-idle">
          {recents.length > 0 && (
            <>
              <div className="search-section-head">
                <span className="search-section-title">Recent</span>
                <button
                  type="button"
                  className="search-section-action"
                  onClick={() => {
                    setRecents([]);
                    writeRecents([]);
                  }}
                >
                  Clear
                </button>
              </div>
              <div className="search-recents">
                {recents.map((r) => (
                  <button type="button" key={r} className="search-recent" onClick={() => runNow(r)}>
                    <SearchIcon size={14} />
                    {r}
                  </button>
                ))}
              </div>
            </>
          )}
          <div className="empty">
            <SearchIcon />
            <span className="empty-text">Search by creator name, video title, or description.</span>
          </div>
        </div>
      )}

      {term.length > 0 && results.isPending && <SearchSkeleton />}

      {settled && hits.length === 0 && !results.isError && (
        <div className="empty">
          <SearchIcon />
          <span className="empty-text">No creators or videos match “{term}”.</span>
        </div>
      )}

      {settled && hits.length > 0 && (
        <div className="search-results" id="search-results" data-testid="search-results" ref={listRef}>
          {showCreators && (
            <section>
              <div className="search-section-head">
                <span className="search-section-title">Creators</span>
              </div>
              <ul className="search-list">
                {creators.map((creator, i) => (
                  <li key={creator.creatorId}>
                    <button
                      type="button"
                      className="search-hit"
                      data-active={active === i}
                      data-testid={`search-creator-${creator.creatorId}`}
                      onMouseEnter={() => setActive(i)}
                      onClick={() => openRow(i)}
                    >
                      <Avatar seed={creator.creatorId} label={creator.displayName} size="lg" />
                      <div className="search-hit-text">
                        <div className="search-hit-name">
                          <Highlight text={creator.displayName} term={term} />
                        </div>
                        <div className="search-hit-sub">
                          {formatHandle(creator.handle, creator.creatorId)} · {creator.videoCount}{' '}
                          {creator.videoCount === 1 ? 'video' : 'videos'}
                        </div>
                      </div>
                      {/* The row drills into this creator's videos rather than
                          opening a player, so it gets a disclosure cue. */}
                      <ChevronRightIcon className="search-hit-cue" size={16} />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {showVideos && (
            <section>
              <div className="search-section-head">
                <span className="search-section-title">
                  {creatorFilter ? `Videos by ${creatorFilter.displayName}` : 'Videos'}
                </span>
                <span className="search-section-count">{videos.length}</span>
              </div>
              <ul className="search-list">
                {videos.map((hit, i) => {
                  const index = (showCreators ? creators.length : 0) + i;
                  return (
                    <li key={hit.videoId}>
                      <button
                        type="button"
                        data-testid={`search-hit-${hit.videoId}`}
                        className="search-hit"
                        data-active={active === index}
                        onMouseEnter={() => setActive(index)}
                        onClick={() => openRow(index)}
                      >
                        <VideoThumb hit={hit} />
                        <div className="search-hit-text">
                          <div className="search-hit-name">
                            <Highlight text={hit.title || 'Untitled video'} term={term} />
                          </div>
                          <div className="search-hit-sub">
                            {hit.creatorDisplayName} · {relativeTime(hit.publishedAt)}
                          </div>
                          {hit.description && (
                            <div className="search-hit-desc">
                              <Highlight text={hit.description} term={term} />
                            </div>
                          )}
                        </div>
                      </button>
                    </li>
                  );
                })}
              </ul>

              {/* The result set used to stop at one page with nothing to say it
                  had, so a term with many matches looked like a term with
                  twenty. */}
              {results.hasNextPage && (
                <button
                  className="btn-ghost btn-sm comment-more"
                  disabled={results.isFetchingNextPage}
                  onClick={() => void results.fetchNextPage()}
                >
                  {results.isFetchingNextPage ? 'Loading…' : 'Load more results'}
                </button>
              )}
            </section>
          )}
        </div>
      )}

      {openHit && (
        <Sheet title={openHit.title || 'Video'} onClose={() => setOpenHit(null)}>
          <SearchHitPlayer hit={openHit} />
        </Sheet>
      )}
    </div>
  );
}

function FilterTab({
  id,
  tab,
  label,
  count,
  onSelect,
}: {
  id: Tab;
  tab: Tab;
  label: string;
  count: number;
  onSelect: (next: Tab) => void;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={tab === id}
      className={`search-filter${tab === id ? ' is-active' : ''}`}
      onClick={() => onSelect(id)}
    >
      {label}
      <span className="search-filter-count">{count}</span>
    </button>
  );
}

/**
 * There are no stored thumbnails, so a row's picture would have to be a real
 * playback session per hit -- far too expensive for a list. A tinted 9:16 tile
 * keyed off the video id at least gives each row a stable, distinct anchor to
 * scan by, in the shape the video will actually open in.
 */
/**
 * A real still from the video, with the coloured placeholder as the fallback it
 * was always meant to be rather than the only thing on offer.
 *
 * <p>The placeholder stays mounted underneath and the image fades in over it, so
 * a tile never flashes empty while the still loads, and a video the worker could
 * not extract a frame from (or one processed before thumbnails existed) simply
 * keeps the placeholder.
 */
export function VideoThumb({ hit }: { hit: SearchHit }) {
  const [failed, setFailed] = useState(false);

  return (
    <span className="search-thumb" style={{ '--h': avatarHue(hit.videoId) } as React.CSSProperties} aria-hidden="true">
      <PlayIcon size={16} />
      {!failed && (
        <img
          className="search-thumb-img"
          src={posterUrl(hit.videoId)}
          alt=""
          loading="lazy"
          decoding="async"
          onError={() => setFailed(true)}
        />
      )}
    </span>
  );
}

/** Marks every occurrence of the term so the reason a row matched is visible. */
function Highlight({ text, term }: { text: string; term: string }) {
  const needle = term.trim().toLowerCase();
  if (!needle) return <>{text}</>;

  const parts: React.ReactNode[] = [];
  const haystack = text.toLowerCase();
  let cursor = 0;
  for (let at = haystack.indexOf(needle); at !== -1; at = haystack.indexOf(needle, cursor)) {
    if (at > cursor) parts.push(text.slice(cursor, at));
    parts.push(
      <mark className="search-mark" key={at}>
        {text.slice(at, at + needle.length)}
      </mark>,
    );
    cursor = at + needle.length;
  }
  if (cursor === 0) return <>{text}</>;
  if (cursor < text.length) parts.push(text.slice(cursor));
  return <>{parts}</>;
}

function SearchSkeleton() {
  return (
    <ul className="search-list" aria-hidden="true">
      {[0, 1, 2, 3].map((i) => (
        <li key={i} className="search-skeleton-row">
          <span className="search-skeleton-thumb" />
          <span className="search-skeleton-lines">
            <span className="search-skeleton-line" />
            <span className="search-skeleton-line is-short" />
          </span>
        </li>
      ))}
    </ul>
  );
}

export function SearchHitPlayer({ hit }: { hit: SearchHit }) {
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

  const counts = useQuery({
    queryKey: ['counts', hit.videoId],
    queryFn: () => getVideoCounts(hit.videoId),
    retry: false,
  });

  // Mirrors Upload's Preview: fetch a session and attach as soon as the sheet
  // opens, rather than making the viewer press play a second time after
  // already clicking in.
  useEffect(() => {
    // Captured while the effect runs, not read at cleanup time: React detaches
    // refs during the commit phase, before passive effect cleanups are flushed,
    // so `videoRef.current` is already null by then and detachHls silently does
    // nothing -- leaving the hls.js instance alive with its MediaSource, segment
    // loaders and retry timers still running. Same reasoning as Upload's Preview.
    const element = videoRef.current;
    session.mutate();
    return () => detachHls(element);
    // `session` is a stable mutation object; re-running on its identity would
    // re-request a playback session on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hit.videoId]);

  return (
    <div>
      <video
        ref={videoRef}
        controls
        autoPlay
        playsInline
        className={`preview-video${orientation === 'landscape' ? ' preview-video-landscape' : ''}`}
        style={{ margin: '0 auto' }}
        onLoadedMetadata={(e) => {
          const { videoWidth, videoHeight } = e.currentTarget;
          if (videoWidth && videoHeight) setOrientation(videoWidth >= videoHeight ? 'landscape' : 'portrait');
        }}
      />
      <div className="upload-meta" style={{ marginTop: '0.85rem' }}>
        <Avatar seed={hit.creatorId} label={hit.creatorDisplayName} size="sm" />
        <div className="search-hit-text">
          <div className="search-hit-name">{hit.creatorDisplayName}</div>
          <div className="search-hit-sub">
            {formatHandle(hit.creatorHandle, hit.creatorId)} · {relativeTime(hit.publishedAt)}
          </div>
        </div>
        {counts.data && (
          <div className="search-hit-counts">
            <span>
              <HeartIcon size={15} /> {counts.data.likeCount}
            </span>
            <span>
              <CommentIcon size={15} /> {counts.data.commentCount}
            </span>
          </div>
        )}
      </div>
      {hit.description && <p className="search-hit-desc is-full">{hit.description}</p>}
      {error && <div className="status-line is-error">{error}</div>}
    </div>
  );
}
