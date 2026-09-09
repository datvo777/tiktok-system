import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  commentOnVideo,
  createPublicSession,
  deleteComment,
  followCreator,
  getCreatorProfile,
  getFeed,
  getFeedItem,
  getVideoCounts,
  isVideoSaved,
  likeVideo,
  posterUrl,
  listComments,
  listReplies,
  recordShare,
  recordView,
  replyToComment,
  submitReport,
  unfollowCreator,
  unlikeVideo,
  ApiError,
  REPORT_REASONS,
  type CommentResponse,
  type ReportReason,
  type ReportSubjectType,
  // Aliased: the api type and the profile *component* below share a name.
  type CreatorProfile as CreatorProfileData,
  type FeedItem,
  type FeedScope,
  type VideoCounts,
} from './api';
import { Sheet } from './App';
import { SaveToCollection } from './Favorites';
import {
  BookmarkIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CommentIcon,
  HeartIcon,
  PlayIcon,
  PlusIcon,
  FlagIcon,
  ShareIcon,
  SparkleIcon,
  VolumeOffIcon,
  VolumeOnIcon,
} from './icons';
import { Avatar, formatCount, formatHandle, relativeTime } from './ui';
import { creatorPath, navigate, shareUrl } from './router';
import { useRequireAccount, useViewer } from './viewer';
import { attachHls, detachHls } from './Upload';

/** Where the viewer's sound preference survives a reload. */
const MUTE_KEY = 'feed:muted';

function readMuted(): boolean {
  try {
    // Default to muted: browsers refuse to autoplay with sound anyway, so an
    // absent preference has only one workable answer.
    return localStorage.getItem(MUTE_KEY) !== 'false';
  } catch {
    return true;
  }
}

/**
 * Sound is a viewer preference, not a per-video one: muting one clip and then
 * scrolling should not un-mute the next. Persisted, because having to un-mute
 * again after every reload is the same annoyance repeated rather than a fresh
 * decision -- and shared by the feed and the single-video view, so arriving on a
 * shared link does not silently reset it.
 */
function useMutedPreference(): [boolean, () => void] {
  const [muted, setMuted] = useState(readMuted);

  useEffect(() => {
    try {
      localStorage.setItem(MUTE_KEY, String(muted));
    } catch {
      /* private mode / quota -- the preference is a convenience, not state we need. */
    }
  }, [muted]);

  return [muted, useCallback(() => setMuted((m) => !m), [])];
}

/**
 * One video on its own, for a URL that names a specific video: a shared link, or
 * a notification about a comment on it. Renders the very same slide the feed
 * does, so every control on it behaves identically and there is no second
 * player to keep in step.
 */
export function VideoView({ videoId }: { videoId: string }) {
  const [muted, toggleMuted] = useMutedPreference();

  const item = useQuery({
    queryKey: ['feedItem', videoId],
    queryFn: () => getFeedItem(videoId),
    retry: false,
  });

  return (
    <div className="feed-viewport is-single">
      {item.isPending && (
        <div className="feed-state">
          <div className="skeleton-player" />
        </div>
      )}

      {item.isError && (
        <div className="feed-state">
          <SparkleIcon size={34} className="empty-glyph" />
          <h2>This video isn&apos;t available</h2>
          <p>
            {item.error instanceof ApiError && item.error.status === 404
              ? 'It may have been removed, made private, or is still being reviewed.'
              : (item.error as Error).message}
          </p>
          <button className="btn-primary" onClick={() => navigate('/')}>
            Go to the feed
          </button>
        </div>
      )}

      {item.data && (
        <FeedSlide item={item.data} isActive preload={false} muted={muted} onToggleMuted={toggleMuted} />
      )}
    </div>
  );
}

export function Feed() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [muted, toggleMuted] = useMutedPreference();
  // Following was implemented on both sides -- you could follow, and it boosted
  // a creator in the ranking -- but there was nowhere to see the result of
  // having followed anyone.
  const [scope, setScope] = useState<FeedScope>('FOR_YOU');

  const feed = useInfiniteQuery({
    queryKey: ['feed', scope],
    queryFn: ({ pageParam }: { pageParam: number }) => getFeed(pageParam, scope),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.page + 1 : undefined),
  });

  // Switching tabs starts a different list; staying on slide 12 of the old one
  // would land the viewer in the middle of the new one.
  useEffect(() => {
    setActiveIndex(0);
    if (containerRef.current) containerRef.current.scrollTop = 0;
  }, [scope]);

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
  // Destructured so the dependency list names the three values this actually
  // uses rather than the whole query object, whose identity changes on every
  // fetch and would re-run this on each one.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = feed;
  useEffect(() => {
    if (items.length === 0) return;
    if (activeIndex >= items.length - 2 && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage();
    }
  }, [activeIndex, items.length, hasNextPage, isFetchingNextPage, fetchNextPage]);

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
        toggleMuted();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [activeIndex, items.length, goTo, toggleMuted]);

  // Always the same element, ref attached unconditionally: the scroll-listener
  // effect above only ever runs once (empty deps), so if this div were instead
  // swapped out for a different one while loading -- as an early return would
  // do -- the ref would be null when that effect runs and never get a second
  // chance to attach once real content replaces it.
  return (
    <>
      <div className="feed-tabs" role="tablist" aria-label="Feed">
        <button
          role="tab"
          aria-selected={scope === 'FOR_YOU'}
          className={`feed-tab${scope === 'FOR_YOU' ? ' is-active' : ''}`}
          onClick={() => setScope('FOR_YOU')}
        >
          For You
        </button>
        <button
          role="tab"
          aria-selected={scope === 'FOLLOWING'}
          className={`feed-tab${scope === 'FOLLOWING' ? ' is-active' : ''}`}
          onClick={() => setScope('FOLLOWING')}
        >
          Following
        </button>
      </div>

      <div className="feed-viewport" ref={containerRef}>
        {feed.isPending && (
          <div className="feed-state">
            <div className="skeleton-player" />
          </div>
        )}

        {!feed.isPending && items.length === 0 && (
          <div className="feed-state">
            <SparkleIcon size={34} className="empty-glyph" />
            {scope === 'FOLLOWING' ? (
              <>
                <h2>Nothing from people you follow</h2>
                <p>Follow a few creators and their new videos will show up here.</p>
                <button className="btn-primary" onClick={() => setScope('FOR_YOU')}>
                  Browse For You
                </button>
              </>
            ) : (
              <>
                <h2>Nothing here yet</h2>
                <p>Published videos show up in this feed. Upload one to get it started.</p>
              </>
            )}
          </div>
        )}

        {items.map((item, i) => (
          <FeedSlide
            key={item.videoId}
            item={item}
            isActive={i === activeIndex}
            // The next slide opens its session and buffers its first segments
            // while the current one is still playing. Without it, the next
            // video only started loading once the viewer had already scrolled
            // to it -- request, HLS attach, first segment -- which is the gap
            // that makes a feed feel slow.
            preload={i === activeIndex + 1}
            muted={muted}
            onToggleMuted={toggleMuted}
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

/** Window a second tap has to land in to count as a double-tap. */
const DOUBLE_TAP_MS = 220;

/**
 * How long a viewer has to watch before it counts as a view. Three seconds is
 * the common convention for short video: long enough to exclude a scroll-past,
 * short enough that a genuine watch of a six-second clip still registers.
 */
const VIEW_THRESHOLD_MS = 3000;

function FeedSlide({
  item,
  isActive,
  preload,
  muted,
  onToggleMuted,
}: {
  item: FeedItem;
  isActive: boolean;
  /**
   * Attach the stream and buffer, but do not play. Only ever true for the one
   * slide after the active one, so at most two sessions are live at a time --
   * the "one video plays" rule is about playback, and a bounded read-ahead does
   * not break it.
   */
  preload: boolean;
  muted: boolean;
  onToggleMuted: () => void;
}) {
  const { viewerId, promptSignIn } = useViewer();
  const requireAccount = useRequireAccount();
  const videoRef = useRef<HTMLVideoElement>(null);
  const [paused, setPaused] = useState(false);
  const [progress, setProgress] = useState(0);
  const [commentOpen, setCommentOpen] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
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

  // Named once so the queries and the optimistic writes below cannot drift
  // apart -- an optimistic update aimed at a slightly different key is a silent
  // no-op, which is the failure mode this avoids.
  const countsKey = ['counts', item.videoId] as const;
  const creatorKey = ['creator', item.creatorId] as const;

  // The feed carries only a creator id. The profile endpoint has the actual
  // display name and follower count, and react-query keys it per creator, so a
  // feed full of one creator's videos still costs a single request.
  const creator = useQuery({
    queryKey: creatorKey,
    queryFn: () => getCreatorProfile(item.creatorId),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const counts = useQuery({
    queryKey: countsKey,
    queryFn: () => getVideoCounts(item.videoId),
    retry: false,
  });

  // Whether this video is in any of the viewer's collections. Its own query
  // rather than a field on counts: saving is private to the viewer, so it has
  // no place in a payload that also carries public totals.
  const saved = useQuery({
    queryKey: ['saved', item.videoId],
    queryFn: () => isVideoSaved(item.videoId),
    retry: false,
  });

  // Read straight from the cache rather than mirrored into local state. The
  // mirror existed to stop a refetch clobbering a click mid-flight, but that is
  // what an optimistic mutation is for -- and a copy kept in step by an effect
  // is one more place for the button and the server to disagree.
  const liked = counts.data?.liked ?? false;
  const following = creator.data?.following ?? false;

  /**
   * `onSuccess` runs after an await, by which point the viewer may already have
   * scrolled on and the deactivation branch below may already have detached this
   * element. Without this guard the stale response attached a fresh MediaSource
   * to an off-screen video and called `play()` on it, so a fast scroll left
   * several hidden slides streaming segments at once -- exactly what the
   * one-active-slide rule exists to prevent.
   */
  const activeRef = useRef(isActive);
  const preloadRef = useRef(preload);

  /**
   * A view is recorded once the viewer has actually watched a meaningful amount
   * -- three seconds, or half the clip if it is shorter than six. Sent at most
   * once per time the slide becomes active, because the count that matters is
   * distinct viewers, and firing on every `timeupdate` would be a request
   * several times a second per playing video.
   */
  const viewReported = useRef(false);
  const watchedMs = useRef(0);

  const play = useMutation({
    mutationFn: () => createPublicSession(item.videoId),
    onSuccess: (result) => {
      // Still wanted? The slide may have scrolled out of range while the
      // request was in flight.
      if (!activeRef.current && !preloadRef.current) return;
      setError(null);
      attachHls(videoRef.current, item.videoId, result.processingVersion, setError);
      // A preloading slide buffers but does not play; it starts when it becomes
      // the active one, by which point the stream is already attached.
      if (activeRef.current) {
        videoRef.current?.play().catch(() => {
          // Autoplay with sound can be refused even while muted, on some
          // browser/OS combinations -- the tap-to-play handler below covers it.
        });
      }
    },
    // A failure the viewer has already scrolled away from is not worth
    // reporting on a slide they are no longer looking at.
    onError: (err) => {
      if (activeRef.current) setError((err as Error).message);
    },
  });

  // Only the one slide the viewer is actually looking at ever holds a live
  // playback session or a decoded frame -- the same "one video plays" rule
  // the real app follows, and it keeps a long feed from opening dozens of
  // concurrent HLS sessions as the viewer scrolls past them.
  useEffect(() => {
    activeRef.current = isActive;
    preloadRef.current = preload;

    if (isActive || preload) {
      // Already attached from a preload -- just start it, rather than opening a
      // second session for a stream this element is already playing.
      if (isActive && videoRef.current?.readyState) {
        void videoRef.current.play().catch(() => {});
      } else if (!play.isPending) {
        play.mutate();
      }
    } else {
      videoRef.current?.pause();
      detachHls(videoRef.current);
      setProgress(0);
      // Reset so scrolling back to a slide counts as a fresh watch decision
      // rather than being suppressed by the earlier one.
      viewReported.current = false;
      watchedMs.current = 0;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isActive, preload]);

  useEffect(() => {
    const element = videoRef.current;
    return () => detachHls(element);
  }, []);

  // Drives the scrubber and the centre play glyph. `timeupdate` fires a few
  // times a second, which is enough for a 3px bar and cheaper than rAF.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const onTime = () => {
      setProgress(video.duration ? video.currentTime / video.duration : 0);

      const played = video.currentTime * 1000;
      watchedMs.current = Math.max(watchedMs.current, played);
      const threshold = video.duration && video.duration < 6 ? (video.duration * 1000) / 2 : VIEW_THRESHOLD_MS;
      if (!viewReported.current && played >= threshold) {
        viewReported.current = true;
        const completed = !!video.duration && video.currentTime / video.duration > 0.9;
        // Best-effort: a failed view record must never interrupt playback, and
        // is not worth telling the viewer about.
        // Only for signed-in viewers: the view model is keyed by account, and a
        // signed-out watch has no viewer to attribute it to. Better an
        // undercounted total than one inflated by anonymous refreshes.
        if (viewerId) void recordView(item.videoId, watchedMs.current, completed).catch(() => {});
      }
    };
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
    // The listeners close over the video id they report a view against; a slide
    // is keyed by that id, so in practice this never changes for one instance.
  }, [item.videoId, viewerId]);

  /**
   * Optimistic, with rollback. The intended next state is still captured before
   * the request — two quick clicks reading the same stale value and both
   * flipping it is what made the button disagree with the server — but the count
   * now moves on the tap instead of after a second round trip to refetch it.
   *
   * <p>`cancelQueries` first, so a counts refetch already in flight cannot land
   * on top of the optimistic write and undo it.
   */
  const like = useMutation({
    mutationFn: async (next: boolean) => {
      if (next) await likeVideo(item.videoId);
      else await unlikeVideo(item.videoId);
      return next;
    },
    onMutate: async (next: boolean) => {
      await queryClient.cancelQueries({ queryKey: countsKey });
      const previous = queryClient.getQueryData<VideoCounts>(countsKey);
      if (previous && previous.liked !== next) {
        queryClient.setQueryData<VideoCounts>(countsKey, {
          ...previous,
          liked: next,
          // Clamped: the cached total can lag reality, and a negative like
          // count on screen is worse than one that is briefly one short.
          likeCount: Math.max(0, previous.likeCount + (next ? 1 : -1)),
        });
      }
      return { previous };
    },
    onError: (_error, _next, context) => {
      if (context?.previous) queryClient.setQueryData(countsKey, context.previous);
    },
    // Reconcile either way: success confirms the guess, failure has already
    // rolled back, and both want the server's real number.
    onSettled: () => void queryClient.invalidateQueries({ queryKey: countsKey }),
  });

  const follow = useMutation({
    mutationFn: async (next: boolean) => {
      if (next) await followCreator(item.creatorId);
      else await unfollowCreator(item.creatorId);
      return next;
    },
    onMutate: async (next: boolean) => {
      await queryClient.cancelQueries({ queryKey: creatorKey });
      const previous = queryClient.getQueryData<CreatorProfileData>(creatorKey);
      if (previous && previous.following !== next) {
        queryClient.setQueryData<CreatorProfileData>(creatorKey, {
          ...previous,
          following: next,
          followerCount: Math.max(0, previous.followerCount + (next ? 1 : -1)),
        });
      }
      return { previous };
    },
    onError: (_error, _next, context) => {
      if (context?.previous) queryClient.setQueryData(creatorKey, context.previous);
    },
    onSettled: () => void queryClient.invalidateQueries({ queryKey: creatorKey }),
  });

  // Paged rather than a single fetch: the server caps a page and hands back a
  // cursor, so a busy video's comments are reachable past the first screenful
  // instead of silently stopping at the old 200-row ceiling.
  const comments = useInfiniteQuery({
    queryKey: ['comments', item.videoId],
    queryFn: ({ pageParam }: { pageParam: string | null }) => listComments(item.videoId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: commentOpen,
  });

  const commentRows = comments.data?.pages.flatMap((p) => p.items) ?? [];

  const submitComment = useMutation({
    mutationFn: () => commentOnVideo(item.videoId, comment),
    onSuccess: () => {
      setComment('');
      void queryClient.invalidateQueries({ queryKey: ['comments', item.videoId] });
      void queryClient.invalidateQueries({ queryKey: ['counts', item.videoId] });
    },
  });

  /** Shared by the scrubber's click and its drag. */
  function seekToClientX(clientX: number, element: HTMLElement) {
    const video = videoRef.current;
    if (!video || !video.duration) return;
    const bounds = element.getBoundingClientRect();
    const fraction = Math.min(1, Math.max(0, (clientX - bounds.left) / bounds.width));
    video.currentTime = fraction * video.duration;
  }

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
    // Resolves to the single-video route, which the router now actually reads.
    // The old `/?v=<id>` link went nowhere: nothing in the app parsed it, so
    // every share opened a generic feed.
    const url = shareUrl(item.videoId);
    try {
      if (navigator.share) await navigator.share({ url, title: item.title ?? 'Short video' });
      else await navigator.clipboard.writeText(url);
      setShareNote('Link copied');
    } catch {
      // A dismissed share sheet rejects too; nothing to report either way.
      return;
    }
    setTimeout(() => setShareNote(null), 1800);
    try {
      await recordShare(item.videoId);
      void queryClient.invalidateQueries({ queryKey: ['counts', item.videoId] });
    } catch {
      // Best-effort: the share itself already succeeded client-side.
    }
  }

  const handle = formatHandle(creator.data?.handle, item.creatorId);
  const creatorName = creator.data?.displayName;
  const followers = creator.data?.followerCount;

  return (
    <div className="feed-slide" data-testid={`feed-card-${item.videoId}`}>
      {/*
        A real button, not `role="button"` with `tabIndex={-1}` — which announced
        itself as a control while being impossible to focus or activate, so the
        player could not be played or paused from a keyboard at all.
      */}
      <button
        type="button"
        className="slide-player"
        onClick={onPlayerTap}
        onDoubleClick={onDoubleTap}
        aria-label={paused ? 'Play video' : 'Pause video'}
        aria-pressed={!paused}
      >
        <video
          ref={videoRef}
          muted={muted}
          playsInline
          className="slide-video"
          // Without a poster each slide was a black rectangle from the moment it
          // scrolled into view until its playback session resolved and the first
          // segment decoded -- the gap that makes a feed feel slow.
          poster={posterUrl(item.videoId)}
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
          <button
            type="button"
            className="slide-creator"
            onClick={(e) => {
              e.stopPropagation();
              navigate(creatorPath(item.creatorId));
            }}
          >
            {handle}
            {creatorName && <span className="slide-creator-name">{creatorName}</span>}
          </button>
          {item.title && <div className="slide-title">{item.title}</div>}
          {item.description && <div className="slide-description">{item.description}</div>}
          {/* Views were tracked by nothing at all before, so a creator had no
              evidence anyone was watching. Distinct viewers, not total plays. */}
          {counts.data !== undefined && counts.data.viewCount > 0 && (
            <div className="slide-views">
              {formatCount(counts.data.viewCount)} {counts.data.viewCount === 1 ? 'view' : 'views'}
            </div>
          )}
          {error && <div className="slide-error">{error}</div>}
        </div>

        {/*
          A slider, not a button. As a `<button>` it seeked on click and nothing
          else: no keyboard seek, no drag, no reported position — so a keyboard
          or screen-reader user could not move through a video at all.
        */}
        <div
          className="slide-progress"
          role="slider"
          tabIndex={0}
          aria-label="Seek"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(progress * 100)}
          aria-valuetext={`${Math.round(progress * 100)}%`}
          onClick={(e) => {
            e.stopPropagation();
            seekToClientX(e.clientX, e.currentTarget);
          }}
          onPointerDown={(e) => {
            e.stopPropagation();
            // Capture, so a drag that leaves the bar keeps scrubbing rather than
            // stopping the moment the pointer crosses the edge.
            e.currentTarget.setPointerCapture(e.pointerId);
          }}
          onPointerMove={(e) => {
            if (e.currentTarget.hasPointerCapture(e.pointerId)) {
              seekToClientX(e.clientX, e.currentTarget);
            }
          }}
          onKeyDown={(e) => {
            const video = videoRef.current;
            if (!video || !video.duration) return;
            // Arrows nudge, Home/End jump. Stopped from bubbling so ArrowUp and
            // ArrowDown scrub here instead of paging the feed.
            const step = e.shiftKey ? 10 : 5;
            if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
              e.preventDefault();
              e.stopPropagation();
              video.currentTime = Math.min(video.duration, video.currentTime + step);
            } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
              e.preventDefault();
              e.stopPropagation();
              video.currentTime = Math.max(0, video.currentTime - step);
            } else if (e.key === 'Home') {
              e.preventDefault();
              video.currentTime = 0;
            } else if (e.key === 'End') {
              e.preventDefault();
              video.currentTime = video.duration;
            }
          }}
        >
          <span className="slide-progress-track">
            <span className="slide-progress-fill" style={{ transform: `scaleX(${progress})` }} />
          </span>
        </div>
      </button>

      <div className="slide-rail">
        {/* Same fix as the player: was `role="button"` with `tabIndex={-1}`,
            so it announced as a control a keyboard could never reach. */}
        <div className="rail-avatar-wrap">
          <button
            type="button"
            className="rail-avatar-btn"
            aria-label={`View ${creatorName ?? handle}'s profile`}
            onClick={() => navigate(creatorPath(item.creatorId))}
          >
            <Avatar seed={item.creatorId} label={creatorName} className="rail-avatar" />
          </button>
          {followers !== undefined && (
            <span className="rail-followers">{formatCount(followers)}</span>
          )}
          {item.creatorId !== viewerId && (
            <button
              className={`rail-follow-pip${following ? ' following' : ''}`}
              onClick={(e) => {
                e.stopPropagation();
                requireAccount(() => follow.mutate(!following))();
              }}
              disabled={follow.isPending}
              aria-pressed={following}
              aria-label={following ? `Unfollow ${creatorName ?? handle}` : `Follow ${creatorName ?? handle}`}
            >
              {following ? <CheckIcon size={12} /> : <PlusIcon size={12} />}
            </button>
          )}
        </div>

        <button
          className={`rail-btn${liked ? ' on' : ''}`}
          onClick={requireAccount(() => like.mutate(!liked))}
          disabled={like.isPending}
          aria-pressed={liked}
          aria-label={liked ? 'Unlike' : 'Like'}
        >
          <span className="rail-btn-glyph">
            <HeartIcon filled={liked} />
          </span>
          {counts.data && <span className="rail-count">{formatCount(counts.data.likeCount)}</span>}
        </button>

        <button className="rail-btn" onClick={() => setCommentOpen(true)} aria-label="Comment">
          <span className="rail-btn-glyph">
            <CommentIcon />
          </span>
          {counts.data && <span className="rail-count">{formatCount(counts.data.commentCount)}</span>}
        </button>

        <button
          className={`rail-btn${saved.data ? ' on' : ''}`}
          onClick={requireAccount(() => setSaveOpen(true))}
          aria-pressed={saved.data ?? false}
          aria-label={saved.data ? 'Saved — change collections' : 'Save to a collection'}
        >
          <span className="rail-btn-glyph">
            <BookmarkIcon filled={saved.data ?? false} />
          </span>
          <span className="rail-count">{saved.data ? 'Saved' : 'Save'}</span>
        </button>

        <button className="rail-btn" onClick={() => void share()} aria-label="Share">
          <span className="rail-btn-glyph">
            <ShareIcon />
          </span>
          {shareNote ? (
            <span className="rail-count">{shareNote}</span>
          ) : (
            counts.data && <span className="rail-count">{formatCount(counts.data.shareCount)}</span>
          )}
        </button>

        {/* Reporting your own video is a mistake rather than a signal, so the
            control is not offered to the creator. */}
        {item.creatorId !== viewerId && (
          <button
            className="rail-btn"
            onClick={requireAccount(() => setReportOpen(true))}
            aria-label="Report this video"
          >
            <span className="rail-btn-glyph">
              <FlagIcon />
            </span>
            <span className="rail-count">Report</span>
          </button>
        )}
      </div>

      {reportOpen && (
        <Sheet title="Report this video" onClose={() => setReportOpen(false)}>
          <ReportForm subjectType="VIDEO" subjectId={item.videoId} onClose={() => setReportOpen(false)} />
        </Sheet>
      )}

      {saveOpen && (
        <Sheet title="Save to collection" onClose={() => setSaveOpen(false)}>
          <SaveToCollection videoId={item.videoId} onClose={() => setSaveOpen(false)} />
        </Sheet>
      )}

      {commentOpen && (
        <Sheet title="Comments" onClose={() => setCommentOpen(false)}>
          {!viewerId && (
            <div className="callout" style={{ marginBottom: '0.85rem' }}>
              <p style={{ marginTop: 0 }}>Sign in to join the conversation.</p>
              <button className="btn-primary btn-sm" onClick={promptSignIn}>
                Sign in
              </button>
            </div>
          )}

          {viewerId && (
          <div className="comment-composer">
            <input
              autoFocus
              placeholder="Say something nice…"
              value={comment}
              maxLength={500}
              onChange={(e) => setComment(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && comment.trim() && !submitComment.isPending) submitComment.mutate();
              }}
            />
            <button
              className="btn-primary"
              disabled={!comment.trim() || submitComment.isPending}
              onClick={() => submitComment.mutate()}
            >
              {submitComment.isPending ? 'Posting…' : 'Post'}
            </button>
          </div>
          )}
          {submitComment.isError && (
            <div className="status-line is-error">{(submitComment.error as Error).message}</div>
          )}

          <div className="comment-list">
            {comments.isPending && <div className="comment-list-state">Loading…</div>}
            {comments.isError && (
              <div className="status-line is-error">{(comments.error as Error).message}</div>
            )}
            {!comments.isPending && commentRows.length === 0 && (
              <div className="comment-list-state">No comments yet. Be the first to say something.</div>
            )}
            {commentRows.map((c) => (
              <CommentThread
                key={c.commentId}
                videoId={item.videoId}
                comment={c}
                viewerId={viewerId}
                videoOwnerId={item.creatorId}
              />
            ))}
            {comments.hasNextPage && (
              <button
                className="btn-ghost btn-sm comment-more"
                disabled={comments.isFetchingNextPage}
                onClick={() => void comments.fetchNextPage()}
              >
                {comments.isFetchingNextPage ? 'Loading…' : 'Load more comments'}
              </button>
            )}
          </div>
        </Sheet>
      )}

    </div>
  );
}

/**
 * One top-level comment and, on request, its replies.
 *
 * <p>Shows the author's real display name where the server supplies one -- the
 * thread previously rendered `@a1b2c3d4e5`, ten hex characters of the author's
 * UUID, for every single row, because there are no usernames in this system and
 * the payload carried no name.
 */
function CommentThread({
  videoId,
  comment,
  viewerId,
  videoOwnerId,
}: {
  videoId: string;
  comment: CommentResponse;
  /** Null when signed out, which is why no delete control appears. */
  viewerId: string | null;
  videoOwnerId: string;
}) {
  const [repliesOpen, setRepliesOpen] = useState(false);
  const [replyOpen, setReplyOpen] = useState(false);
  const [replyText, setReplyText] = useState('');
  const queryClient = useQueryClient();

  const replies = useInfiniteQuery({
    queryKey: ['replies', videoId, comment.commentId],
    queryFn: ({ pageParam }: { pageParam: string | null }) =>
      listReplies(videoId, comment.commentId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: repliesOpen,
  });

  const replyRows = replies.data?.pages.flatMap((p) => p.items) ?? [];

  const submitReply = useMutation({
    mutationFn: () => replyToComment(videoId, comment.commentId, replyText),
    onSuccess: () => {
      setReplyText('');
      setReplyOpen(false);
      setRepliesOpen(true);
      void queryClient.invalidateQueries({ queryKey: ['replies', videoId, comment.commentId] });
      void queryClient.invalidateQueries({ queryKey: ['comments', videoId] });
      void queryClient.invalidateQueries({ queryKey: ['counts', videoId] });
    },
  });

  return (
    <div className="comment-thread">
      <CommentRow
        videoId={videoId}
        comment={comment}
        viewerId={viewerId}
        videoOwnerId={videoOwnerId}
        actions={
          <>
            {viewerId && (
              <button className="comment-row-action" onClick={() => setReplyOpen((v) => !v)}>
                Reply
              </button>
            )}
            {comment.replyCount > 0 && (
              <button className="comment-row-action" onClick={() => setRepliesOpen((v) => !v)}>
                {repliesOpen
                  ? 'Hide replies'
                  : `View ${comment.replyCount} ${comment.replyCount === 1 ? 'reply' : 'replies'}`}
              </button>
            )}
          </>
        }
      >
        {replyOpen && (
          <div className="comment-composer comment-reply-composer">
            <input
              autoFocus
              placeholder={`Reply to ${authorLabel(comment)}…`}
              value={replyText}
              maxLength={500}
              onChange={(e) => setReplyText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && replyText.trim() && !submitReply.isPending) submitReply.mutate();
              }}
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
            {replyRows.map((r) => (
              <CommentRow
                key={r.commentId}
                videoId={videoId}
                comment={r}
                viewerId={viewerId}
                videoOwnerId={videoOwnerId}
              />
            ))}
            {replies.hasNextPage && (
              <button
                className="btn-ghost btn-sm comment-more"
                disabled={replies.isFetchingNextPage}
                onClick={() => void replies.fetchNextPage()}
              >
                {replies.isFetchingNextPage ? 'Loading…' : 'Load more replies'}
              </button>
            )}
          </div>
        )}
      </CommentRow>
    </div>
  );
}

/** The display name where there is one, falling back to the id-derived handle. */
function authorLabel(comment: CommentResponse): string {
  return comment.authorDisplayName ?? formatHandle(comment.authorHandle, comment.accountId);
}

/**
 * A single comment, shared by top-level rows and replies so both carry the same
 * author, timestamp and delete affordance.
 */
function CommentRow({
  videoId,
  comment,
  viewerId,
  videoOwnerId,
  actions,
  children,
}: {
  videoId: string;
  comment: CommentResponse;
  viewerId: string | null;
  videoOwnerId: string;
  actions?: React.ReactNode;
  children?: React.ReactNode;
}) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);

  // The author may remove their own comment; the video's owner may remove any
  // comment on their video. Anything else the server refuses anyway -- this only
  // decides whether to offer the control.
  const canDelete = viewerId !== null && (comment.accountId === viewerId || videoOwnerId === viewerId);

  const remove = useMutation({
    mutationFn: () => deleteComment(videoId, comment.commentId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['comments', videoId] });
      void queryClient.invalidateQueries({ queryKey: ['counts', videoId] });
      if (comment.parentCommentId) {
        void queryClient.invalidateQueries({ queryKey: ['replies', videoId, comment.parentCommentId] });
      }
    },
  });

  return (
    <div className="comment-row">
      <Avatar seed={comment.accountId} label={comment.authorDisplayName ?? undefined} size="sm" className="comment-avatar" />
      <div className="comment-row-body">
        <span className="comment-row-author">
          {authorLabel(comment)}
          {comment.authorHandle && (
            <span className="comment-row-handle">{formatHandle(comment.authorHandle)}</span>
          )}
          <span className="comment-row-time" title={comment.createdAt}>
            {relativeTime(comment.createdAt)}
          </span>
        </span>
        <span className="comment-row-text">{comment.body}</span>
        <div className="comment-row-actions">
          {actions}
          {canDelete &&
            (confirming ? (
              <>
                <button
                  className="comment-row-action is-danger"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate()}
                >
                  {remove.isPending ? 'Deleting…' : 'Confirm delete'}
                </button>
                <button className="comment-row-action" onClick={() => setConfirming(false)}>
                  Cancel
                </button>
              </>
            ) : (
              <button className="comment-row-action" onClick={() => setConfirming(true)}>
                Delete
              </button>
            ))}
        </div>
        {remove.isError && <div className="status-line is-error">{(remove.error as Error).message}</div>}
        {children}
      </div>
    </div>
  );
}

/**
 * The report form, shared by videos, accounts and comments — a report is the
 * same act whatever it names.
 *
 * <p>Detail is optional on purpose. A required free-text box is how a report
 * form ends up collecting abuse aimed at the person being reported, and the
 * fixed reason list already carries what a moderator triages on.
 */
export function ReportForm({
  subjectType,
  subjectId,
  onClose,
}: {
  subjectType: ReportSubjectType;
  subjectId: string;
  onClose: () => void;
}) {
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [detail, setDetail] = useState('');

  const send = useMutation({
    mutationFn: () => submitReport(subjectType, subjectId, reason as ReportReason, detail),
  });

  if (send.isSuccess) {
    return (
      <div>
        <div className="callout">
          <div className="callout-title">
            <CheckIcon /> Thanks — this has been sent for review.
          </div>
          <p>
            We look at every report. Nothing about this video changes unless a moderator decides it
            breaks the rules.
          </p>
        </div>
        <button className="btn-primary btn-block" style={{ marginTop: '1rem' }} onClick={onClose}>
          Done
        </button>
      </div>
    );
  }

  return (
    <div>
      <p style={{ marginBottom: '0.9rem' }}>What&apos;s wrong with it?</p>

      <div className="report-reasons" role="radiogroup" aria-label="Reason for reporting">
        {REPORT_REASONS.map((option) => (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={reason === option.value}
            className={`report-reason${reason === option.value ? ' is-selected' : ''}`}
            onClick={() => setReason(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>

      <label className="field" style={{ marginTop: '1rem' }}>
        <span className="field-label">Anything else? (optional)</span>
        <textarea
          value={detail}
          maxLength={1000}
          rows={3}
          placeholder="Add context that would help a moderator."
          onChange={(e) => setDetail(e.target.value)}
        />
      </label>

      <button
        className="btn-primary btn-block"
        disabled={!reason || send.isPending}
        onClick={() => send.mutate()}
      >
        {send.isPending ? 'Sending…' : 'Submit report'}
      </button>

      {send.isError && <div className="status-line is-error">{(send.error as Error).message}</div>}
    </div>
  );
}
