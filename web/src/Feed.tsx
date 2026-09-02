import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import {
  commentOnVideo,
  createPublicSession,
  getFeed,
  likeVideo,
  unlikeVideo,
  type FeedItem,
} from './api';
import { attachHls, detachHls } from './Upload';

export function Feed() {
  const [page, setPage] = useState(0);
  const feed = useQuery({ queryKey: ['feed', page], queryFn: () => getFeed(page) });

  return (
    <section className="card">
      <div className="card-head">
        <h2>Feed</h2>
        <span className="card-eyebrow">Milestone 4</span>
      </div>
      <p className="card-desc">
        Rule-based, Redis-cached, and filtered by eligibility/revocation server-side — only videos that have been
        transcoded, approved, and published appear here.
      </p>

      <button className="btn-sm" onClick={() => feed.refetch()} disabled={feed.isFetching}>
        {feed.isFetching ? 'Loading...' : '↻ Refresh feed'}
      </button>

      {feed.isSuccess && feed.data.items.length === 0 && (
        <div className="feed-empty">Nothing published yet. Upload and publish a video to see it here.</div>
      )}

      {feed.data && feed.data.items.length > 0 && (
        <div className="feed-grid">
          {feed.data.items.map((item) => (
            <FeedCard key={item.videoId} item={item} />
          ))}
        </div>
      )}

      <div className="pager">
        <button className="btn-sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
          ← Previous
        </button>
        <span className="pager-label">page {page}</span>
        {/* Disabled on the last page: Next was previously always enabled, so a
            user could page indefinitely into empty results. */}
        <button
          className="btn-sm"
          onClick={() => setPage((p) => p + 1)}
          disabled={!feed.data?.hasMore || feed.isFetching}
        >
          Next →
        </button>
      </div>
    </section>
  );
}

function FeedCard({ item }: { item: FeedItem }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [log, setLog] = useState('');
  const [liked, setLiked] = useState(false);
  const [comment, setComment] = useState('');
  const queryClient = useQueryClient();

  // See the same cleanup in Upload.tsx: the ref is already null once passive
  // effect cleanups run on unmount, so the element must be captured here.
  useEffect(() => {
    const element = videoRef.current;
    return () => detachHls(element);
  }, []);

  const play = useMutation({
    mutationFn: () => createPublicSession(item.videoId),
    onMutate: () => setLog('Requesting playback session...'),
    onSuccess: (result) => {
      setLog('Playing.');
      attachHls(videoRef.current, item.videoId, result.processingVersion, setLog);
    },
    onError: (error) => setLog(`Denied: ${(error as Error).message}`),
  });

  /**
   * The intended next state is captured before the request rather than toggled
   * in onSuccess: two quick clicks both read the same stale `liked` and both
   * flipped it, so the button could end up disagreeing with the server. The feed
   * response carries no per-viewer like state, so this is still optimistic —
   * it just no longer races itself.
   */
  const like = useMutation({
    mutationFn: async () => {
      const next = !liked;
      if (next) await likeVideo(item.videoId);
      else await unlikeVideo(item.videoId);
      return next;
    },
    onSuccess: (next) => setLiked(next),
  });

  const submitComment = useMutation({
    mutationFn: () => commentOnVideo(item.videoId, comment),
    onSuccess: () => {
      setComment('');
      void queryClient.invalidateQueries({ queryKey: ['feed'] });
    },
  });

  return (
    <div data-testid={`feed-card-${item.videoId}`} className="feed-card">
      <video ref={videoRef} controls className="feed-card-video" />
      <div className="feed-card-body">
        {item.title && <div className="feed-card-title">{item.title}</div>}
        {item.description && <div className="feed-card-description">{item.description}</div>}
        <div className="feed-card-ids" title={`video: ${item.videoId}`}>
          {item.videoId}
        </div>
        <div className="feed-card-ids" title={`creator: ${item.creatorId}`}>
          by {item.creatorId.slice(0, 8)}…
        </div>

        <div className="feed-card-actions">
          <button className="btn-sm icon-btn" onClick={() => play.mutate()} disabled={play.isPending}>
            {play.isPending ? '…' : '▶'}
          </button>
          <button
            className={`btn-sm icon-btn${liked ? ' btn-primary' : ''}`}
            onClick={() => like.mutate()}
            disabled={like.isPending}
          >
            {liked ? '♥ Liked' : '♡ Like'}
          </button>
        </div>

        <div className="feed-card-comment-row">
          <input
            placeholder="Add a comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          <button
            className="btn-sm"
            onClick={() => submitComment.mutate()}
            disabled={!comment || submitComment.isPending}
          >
            ↵
          </button>
        </div>

        {log && <p style={{ fontSize: '0.72rem', marginTop: '0.4rem' }}>{log}</p>}
      </div>
    </div>
  );
}
