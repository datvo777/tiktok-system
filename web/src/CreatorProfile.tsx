import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  followCreator,
  getCreatorProfile,
  getCreatorVideos,
  unfollowCreator,
  type SearchHit,
} from './api';
import { Sheet } from './App';
import { SearchHitPlayer, VideoThumb } from './SearchPanel';
import { Avatar, formatCount, handleFor } from './ui';

/**
 * A read-only public profile: header stats plus the creator's published
 * videos, reusing the same grid/player the search panel already built for
 * "browse videos I didn't upload myself" -- there is no separate thumbnail or
 * detail view to maintain.
 */
export function CreatorProfile({ creatorId, viewerId }: { creatorId: string; viewerId: string }) {
  const [page, setPage] = useState(0);
  const [openVideo, setOpenVideo] = useState<SearchHit | null>(null);
  const [following, setFollowing] = useState(false);
  const queryClient = useQueryClient();
  const isSelf = creatorId === viewerId;

  const profile = useQuery({
    queryKey: ['creator', creatorId],
    queryFn: () => getCreatorProfile(creatorId),
    retry: false,
  });

  const videos = useQuery({
    queryKey: ['creatorVideos', creatorId, page],
    queryFn: () => getCreatorVideos(creatorId, page),
    retry: false,
  });

  // Same reasoning as the feed rail's follow pip: the profile query is the
  // source of truth until the viewer acts on this button.
  useEffect(() => {
    if (profile.data) setFollowing(profile.data.following);
  }, [profile.data]);

  const follow = useMutation({
    mutationFn: async (next: boolean) => {
      if (next) await followCreator(creatorId);
      else await unfollowCreator(creatorId);
      return next;
    },
    onSuccess: (next) => {
      setFollowing(next);
      void queryClient.invalidateQueries({ queryKey: ['creator', creatorId] });
    },
  });

  if (profile.isPending) {
    return (
      <div className="status-line">
        <span className="spinner" style={{ width: 15, height: 15, borderWidth: 2 }} /> Loading profile...
      </div>
    );
  }
  if (profile.isError) {
    return <div className="status-line is-error">{(profile.error as Error).message}</div>;
  }

  return (
    <div className="creator-profile">
      <div className="creator-profile-head">
        <Avatar seed={creatorId} label={profile.data.displayName} size="lg" />
        <div className="creator-profile-name">{profile.data.displayName}</div>
        <div className="creator-profile-handle">{handleFor(creatorId)}</div>
        <div className="creator-profile-stats">
          <span>
            <strong>{formatCount(profile.data.followerCount)}</strong> Followers
          </span>
          <span>
            <strong>{formatCount(profile.data.followingCount)}</strong> Following
          </span>
        </div>
        {!isSelf && (
          <button
            className={`btn-primary btn-sm creator-profile-follow${following ? ' is-following' : ''}`}
            onClick={() => follow.mutate(!following)}
            disabled={follow.isPending}
            aria-pressed={following}
          >
            {following ? 'Following' : 'Follow'}
          </button>
        )}
      </div>

      {videos.isPending && <div className="status-line">Loading videos...</div>}
      {videos.isError && <div className="status-line is-error">{(videos.error as Error).message}</div>}
      {videos.data && videos.data.items.length === 0 && page === 0 && (
        <div className="status-line">No videos yet.</div>
      )}

      {videos.data && videos.data.items.length > 0 && (
        <div className="creator-video-grid">
          {videos.data.items.map((video) => {
            const hit: SearchHit = {
              videoId: video.videoId,
              creatorId,
              creatorDisplayName: profile.data.displayName,
              title: video.title,
              description: video.description,
              publishedAt: video.publishedAt,
            };
            return (
              <button
                key={video.videoId}
                type="button"
                className="creator-video-tile"
                onClick={() => setOpenVideo(hit)}
              >
                <VideoThumb hit={hit} />
                <span className="creator-video-tile-title">{video.title || 'Untitled video'}</span>
              </button>
            );
          })}
        </div>
      )}

      {videos.data && (page > 0 || videos.data.hasMore) && (
        <div className="btn-row" style={{ marginTop: '1rem' }}>
          <button
            className="btn-ghost btn-sm"
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Previous
          </button>
          <button className="btn-ghost btn-sm" disabled={!videos.data.hasMore} onClick={() => setPage((p) => p + 1)}>
            Next
          </button>
        </div>
      )}

      {openVideo && (
        <Sheet title={openVideo.title || 'Video'} onClose={() => setOpenVideo(null)}>
          <SearchHitPlayer hit={openVideo} />
        </Sheet>
      )}
    </div>
  );
}
