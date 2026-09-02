import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  commentOnVideo,
  createPublicSession,
  getCreatorProfile,
  getFeed,
  likeVideo,
  listComments,
  listReplies,
  replyToComment,
  unlikeVideo,
  type CommentResponse,
  type FeedItem,
} from './api';
import { Sheet } from './App';
import {
  ChevronDownIcon,
  ChevronUpIcon,
  CommentIcon,
  HeartIcon,
  PlayIcon,
  ShareIcon,
  SparkleIcon,
  VolumeOffIcon,
  VolumeOnIcon,
} from './icons';
import { Avatar, handleFor } from './ui';
import { attachHls, detachHls } from './Upload';

export function Feed() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  // Sound is a viewer preference, not a per-video one: muting one clip and
  // then scrolling should not un-mute the next, so it lives up here.
  const [muted, setMuted] = useState(true);

  const feed = useInfiniteQuery({
    queryKey: ['feed'],
    queryFn: ({ pageParam }: { pageParam: number }) => getFeed(pageParam),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.page + 1 : undefined),
  });

  const items: FeedItem[] = feed.data?.pages.flatMap((p) => p.items) ?? [];

  // One video fills the viewport and scroll-snaps to the next, so "which
  // slide is active" is just which one is currently aligned to the top of
  // the scroll container -- no IntersectionObserver needed.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    let ticking = false;
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        setActiveIndex(Math.round(el.scrollTop / el.clientHeight));
        ticking = false;
      });
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  // Fetch the next page a couple of slides before the viewer actually runs out.
  useEffect(() => {
    if (items.length === 0) return;
    if (activeIndex >= items.length - 2 && feed.hasNextPage && !feed.isFetchingNextPage) {
      void feed.fetchNextPage();
    }
  }, [activeIndex, items.length, feed.hasNextPage, feed.isFetchingNextPage, feed.fetchNextPage]);

  const goTo = useCallback((index: number) => {
    const el = containerRef.current;
    if (!el) return;
    const top = index * el.clientHeight;
    const from = el.scrollTop;
    el.scrollTo({ top, behavior: 'smooth' });
    // Where smooth scrolling is switched off -- some embedded browsers, some
    // OS accessibility settings -- `behavior: 'smooth'` is treated as a no-op
    // rather than falling back to an instant jump, which left these buttons
    // and the arrow keys doing nothing at all. If nothing moved, jump.
    window.setTimeout(() => {
      if (el.scrollTop === from) el.scrollTop = top;
    }, 120);
  }, []);

  // Desktop viewers have no swipe gesture, so the arrow keys are the paging
  // control that the on-screen chevrons duplicate.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        goTo(Math.min(activeIndex + 1, items.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        goTo(Math.max(activeIndex - 1, 0));
      } else if (e.key.toLowerCase() === 'm') {
        setMuted((m) => !m);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [activeIndex, items.length, goTo]);

  // Always the same element, ref attached unconditionally: the scroll-listener
  // effect above only ever runs once (empty deps), so if this div were instead
  // swapped out for a different one while loading -- as an early return would
  // do -- the ref would be null when that effect runs and never get a second
  // chance to attach once real content replaces it.
  return (
    <>
      <div className="feed-viewport" ref={containerRef}>
        {feed.isPending && (
          <div className="feed-state">
            <div className="skeleton-player" />
          </div>
        )}

        {!feed.isPending && items.length === 0 && (
          <div className="feed-state">
            <SparkleIcon size={34} className="empty-glyph" />
            <h2>Nothing here yet</h2>
            <p>Published videos show up in this feed. Upload one to get it started.</p>
          </div>
        )}

        {items.map((item, i) => (
          <FeedSlide
            key={item.videoId}
            item={item}
            isActive={i === activeIndex}
            muted={muted}
            onToggleMuted={() => setMuted((m) => !m)}
          />
        ))}
      </div>

      {items.length > 1 && (
        <div className="feed-pager">
          <button onClick={() => goTo(activeIndex - 1)} disabled={activeIndex === 0} aria-label="Previous video">
            <ChevronUpIcon size={20} />
          </button>
          <button
            onClick={() => goTo(activeIndex + 1)}
            disabled={activeIndex >= items.length - 1}
            aria-label="Next video"
          >
            <ChevronDownIcon size={20} />
          </button>
        </div>
      )}

      {feed.isFetchingNextPage && (
        <div className="feed-loading-more">
          <div className="spinner" />
        </div>
      )}
    </>
  );
}

/** 1200 -> "1.2K": rail labels have room for four characters, not four digits. */
/** Window a second tap has to land in to count as a double-tap. */
const DOUBLE_TAP_MS = 220;

function formatCount(value: number): string {
  if (value < 1000) return String(value);
  if (value < 1_000_000) return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}K`.replace('.0', '');
  return `${(value / 1_000_000).toFixed(1)}M`.replace('.0', '');
}

function FeedSlide({
  item,
  isActive,
  muted,
  onToggleMuted,
}: {
  item: FeedItem;
  isActive: boolean;
  muted: boolean;
  onToggleMuted: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [liked, setLiked] = useState(false);
  const [paused, setPaused] = useState(false);
  const [progress, setProgress] = useState(0);
  const [commentOpen, setCommentOpen] = useState(false);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [shareNote, setShareNote] = useState<string | null>(null);
  // Bumped on every double-tap so the burst element remounts and its
  // animation replays; a plain boolean would only fire once.
  const [burst, setBurst] = useState(0);
  const tapTimer = useRef<number | null>(null);
  const looping = useRef(false);
  const queryClient = useQueryClient();

  useEffect(() => () => {
    if (tapTimer.current !== null) clearTimeout(tapTimer.current);
  }, []);

  // The feed carries only a creator id. The profile endpoint has the actual
  // display name and follower count, and react-query keys it per creator, so a
  // feed full of one creator's videos still costs a single request.
  const creator = useQuery({
    queryKey: ['creator', item.creatorId],
    queryFn: () => getCreatorProfile(item.creatorId),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const play = useMutation({
    mutationFn: () => createPublicSession(item.videoId),
    onSuccess: (result) => {
      setError(null);
      attachHls(videoRef.current, item.videoId, result.processingVersion, setError);
      videoRef.current?.play().catch(() => {
        // Autoplay with sound can be refused even while muted, on some
        // browser/OS combinations -- the tap-to-play handler below covers it.
      });
    },
    onError: (err) => setError((err as Error).message),
  });

  // Only the one slide the viewer is actually looking at ever holds a live
  // playback session or a decoded frame -- the same "one video plays" rule
  // the real app follows, and it keeps a long feed from opening dozens of
  // concurrent HLS sessions as the viewer scrolls past them.
  useEffect(() => {
    if (isActive) {
      play.mutate();
    } else {
      videoRef.current?.pause();
      detachHls(videoRef.current);
      setProgress(0);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isActive]);

  useEffect(() => {
    const element = videoRef.current;
    return () => detachHls(element);
  }, []);

  // Drives the scrubber and the centre play glyph. `timeupdate` fires a few
  // times a second, which is enough for a 3px bar and cheaper than rAF.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const onTime = () => setProgress(video.duration ? video.currentTime / video.duration : 0);
    const onPlay = () => {
      looping.current = false;
      setPaused(false);
    };
    // Rewinding emits a `pause` of its own, which was flashing the centre play
    // glyph once per loop -- a visible blink every few seconds on a short clip.
    // The flag marks that one pause as ours so it doesn't reach the UI.
    const onPause = () => {
      if (!looping.current) setPaused(true);
    };
    // The `loop` attribute does not survive hls.js: once the MediaSource is
    // ended the element stalls on the last frame instead of rewinding, which
    // looked exactly like a frozen video. Rewinding by hand does work.
    const onEnded = () => {
      looping.current = true;
      video.currentTime = 0;
      void video.play().catch(() => {
        looping.current = false;
        setPaused(true);
      });
    };
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onEnded);
    return () => {
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onEnded);
    };
  }, []);

  /**
   * The intended next state is captured before the request rather than toggled
   * in onSuccess: two quick clicks both read the same stale `liked` and both
   * flipped it, so the button could end up disagreeing with the server. The feed
   * response carries no per-viewer like state, so this is still optimistic —
   * it just no longer races itself.
   */
  const like = useMutation({
    mutationFn: async (next: boolean) => {
      if (next) await likeVideo(item.videoId);
      else await unlikeVideo(item.videoId);
      return next;
    },
    onSuccess: (next) => setLiked(next),
  });

  const comments = useQuery({
    queryKey: ['comments', item.videoId],
    queryFn: () => listComments(item.videoId),
    enabled: commentOpen,
  });

  const submitComment = useMutation({
    mutationFn: () => commentOnVideo(item.videoId, comment),
    onSuccess: () => {
      setComment('');
      void queryClient.invalidateQueries({ queryKey: ['comments', item.videoId] });
    },
  });

  function togglePlayback() {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) void video.play().catch(() => {});
    else video.pause();
  }

  /**
   * A double-click delivers two `click` events before `dblclick`, so tapping
   * twice to like was also toggling playback twice -- a visible stutter that
   * sometimes left the video paused. Hold the single-tap action just long
   * enough for a second tap to cancel it.
   */
  function onPlayerTap() {
    if (tapTimer.current !== null) return;
    tapTimer.current = window.setTimeout(() => {
      tapTimer.current = null;
      togglePlayback();
    }, DOUBLE_TAP_MS);
  }

  // Double-tap-to-like only ever likes, never un-likes -- an accidental second
  // double-tap taking a like away is the more annoying failure.
  function onDoubleTap() {
    if (tapTimer.current !== null) {
      clearTimeout(tapTimer.current);
      tapTimer.current = null;
    }
    setBurst((n) => n + 1);
    if (!liked && !like.isPending) like.mutate(true);
  }

  async function share() {
    const url = `${window.location.origin}/?v=${item.videoId}`;
    try {
      if (navigator.share) await navigator.share({ url, title: item.title ?? 'Short video' });
      else await navigator.clipboard.writeText(url);
      setShareNote('Link copied');
    } catch {
      // A dismissed share sheet rejects too; nothing to report either way.
      return;
    }
    setTimeout(() => setShareNote(null), 1800);
  }

  const handle = handleFor(item.creatorId);
  const creatorName = creator.data?.displayName;
  const followers = creator.data?.followerCount;

  return (
    <div className="feed-slide" data-testid={`feed-card-${item.videoId}`}>
      <div
        className="slide-player"
        onClick={onPlayerTap}
        onDoubleClick={onDoubleTap}
        role="button"
        tabIndex={-1}
        aria-label={paused ? 'Play video' : 'Pause video'}
      >
        <video
          ref={videoRef}
          muted={muted}
          playsInline
          className="slide-video"
          onCanPlay={() => {
            if (isActive) videoRef.current?.play().catch(() => {});
          }}
        />

        <div className="slide-scrim-top" />
        <div className="slide-scrim" />

        <div className={`play-badge${paused ? ' visible' : ''}`}>
          <PlayIcon />
        </div>

        {burst > 0 && (
          <div className="heart-burst" key={burst}>
            <HeartIcon filled />
          </div>
        )}

        <button
          className="slide-mute"
          onClick={(e) => {
            e.stopPropagation();
            onToggleMuted();
          }}
          aria-label={muted ? 'Unmute' : 'Mute'}
        >
          {muted ? <VolumeOffIcon size={19} /> : <VolumeOnIcon size={19} />}
        </button>

        <div className="slide-info">
          <div className="slide-creator">
            {handle}
            {creatorName && <span className="slide-creator-name">{creatorName}</span>}
          </div>
          {item.title && <div className="slide-title">{item.title}</div>}
          {item.description && <div className="slide-description">{item.description}</div>}
          {error && <div className="slide-error">{error}</div>}
        </div>

        <button
          className="slide-progress"
          aria-label="Seek"
          onClick={(e) => {
            e.stopPropagation();
            const video = videoRef.current;
            if (!video || !video.duration) return;
            const bounds = e.currentTarget.getBoundingClientRect();
            video.currentTime = ((e.clientX - bounds.left) / bounds.width) * video.duration;
          }}
        >
          <span className="slide-progress-track">
            <span className="slide-progress-fill" style={{ transform: `scaleX(${progress})` }} />
          </span>
        </button>
      </div>

      <div className="slide-rail">
        <span className="rail-avatar-wrap">
          <Avatar seed={item.creatorId} label={creatorName} className="rail-avatar" />
          {followers !== undefined && (
            <span className="rail-followers">{formatCount(followers)}</span>
          )}
        </span>

        <button
          className={`rail-btn${liked ? ' on' : ''}`}
          onClick={() => like.mutate(!liked)}
          disabled={like.isPending}
          aria-pressed={liked}
          aria-label={liked ? 'Unlike' : 'Like'}
        >
          <span className="rail-btn-glyph">
            <HeartIcon filled={liked} />
          </span>
        </button>

        <button className="rail-btn" onClick={() => setCommentOpen(true)} aria-label="Comment">
          <span className="rail-btn-glyph">
            <CommentIcon />
          </span>
        </button>

        <button className="rail-btn" onClick={() => void share()} aria-label="Share">
          <span className="rail-btn-glyph">
            <ShareIcon />
          </span>
          {shareNote && <span className="rail-count">{shareNote}</span>}
        </button>
      </div>

      {commentOpen && (
        <Sheet title="Comments" onClose={() => setCommentOpen(false)}>
          <div className="comment-composer">
            <input
              autoFocus
              placeholder="Say something nice…"
              value={comment}
              maxLength={500}
              onChange={(e) => setComment(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && comment.trim() && submitComment.mutate()}
            />
            <button
              className="btn-primary"
              disabled={!comment.trim() || submitComment.isPending}
              onClick={() => submitComment.mutate()}
            >
              {submitComment.isPending ? 'Posting…' : 'Post'}
            </button>
          </div>
          {submitComment.isError && (
            <div className="status-line is-error">{(submitComment.error as Error).message}</div>
          )}

          <div className="comment-list">
            {comments.isPending && <div className="comment-list-state">Loading…</div>}
            {comments.isError && (
              <div className="status-line is-error">{(comments.error as Error).message}</div>
            )}
            {comments.data?.length === 0 && (
              <div className="comment-list-state">No comments yet. Be the first to say something.</div>
            )}
            {comments.data?.map((c) => (
              <CommentThread key={c.commentId} videoId={item.videoId} comment={c} />
            ))}
          </div>
        </Sheet>
      )}
    </div>
  );
}

function CommentThread({ videoId, comment }: { videoId: string; comment: CommentResponse }) {
  const [repliesOpen, setRepliesOpen] = useState(false);
  const [replyOpen, setReplyOpen] = useState(false);
  const [replyText, setReplyText] = useState('');
  const queryClient = useQueryClient();

  const replies = useQuery({
    queryKey: ['replies', videoId, comment.commentId],
    queryFn: () => listReplies(videoId, comment.commentId),
    enabled: repliesOpen,
  });

  const submitReply = useMutation({
    mutationFn: () => replyToComment(videoId, comment.commentId, replyText),
    onSuccess: () => {
      setReplyText('');
      setReplyOpen(false);
      setRepliesOpen(true);
      void queryClient.invalidateQueries({ queryKey: ['replies', videoId, comment.commentId] });
      void queryClient.invalidateQueries({ queryKey: ['comments', videoId] });
    },
  });

  return (
    <div className="comment-thread">
      <div className="comment-row">
        <Avatar seed={comment.accountId} size="sm" className="comment-avatar" />
        <div className="comment-row-body">
          <span className="comment-row-author">{handleFor(comment.accountId)}</span>
          <span className="comment-row-text">{comment.body}</span>
          <div className="comment-row-actions">
            <button className="comment-row-action" onClick={() => setReplyOpen((v) => !v)}>
              Reply
            </button>
            {comment.replyCount > 0 && (
              <button className="comment-row-action" onClick={() => setRepliesOpen((v) => !v)}>
                {repliesOpen ? 'Hide replies' : `View ${comment.replyCount} ${comment.replyCount === 1 ? 'reply' : 'replies'}`}
              </button>
            )}
          </div>

          {replyOpen && (
            <div className="comment-composer comment-reply-composer">
              <input
                autoFocus
                placeholder={`Reply to ${handleFor(comment.accountId)}…`}
                value={replyText}
                maxLength={500}
                onChange={(e) => setReplyText(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && replyText.trim() && submitReply.mutate()}
              />
              <button
                className="btn-primary"
                disabled={!replyText.trim() || submitReply.isPending}
                onClick={() => submitReply.mutate()}
              >
                {submitReply.isPending ? 'Posting…' : 'Post'}
              </button>
            </div>
          )}
          {submitReply.isError && (
            <div className="status-line is-error">{(submitReply.error as Error).message}</div>
          )}

          {repliesOpen && (
            <div className="comment-replies">
              {replies.isPending && <div className="comment-list-state">Loading…</div>}
              {replies.isError && (
                <div className="status-line is-error">{(replies.error as Error).message}</div>
              )}
              {replies.data?.map((r) => (
                <div className="comment-row" key={r.commentId}>
                  <Avatar seed={r.accountId} size="sm" className="comment-avatar" />
                  <div className="comment-row-body">
                    <span className="comment-row-author">{handleFor(r.accountId)}</span>
                    <span className="comment-row-text">{r.body}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
