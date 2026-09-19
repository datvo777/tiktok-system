import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { CheckIcon, FlagIcon } from './icons';
import {
  deleteVideo,
  getAppealStatus,
  getMyVideos,
  getVideoCounts,
  publishVideo,
  submitAppeal,
  unpublishVideo,
  type AppealResponse,
  type VideoSummary,
} from './api';
import { navigate, videoPath } from './router';
import { VideoThumb } from './SearchPanel';
import { formatCount, relativeTime } from './ui';

/**
 * Companion to Upload.tsx's live-poll status view: that one only shows the
 * video that is still open in the upload sheet. This is the history a creator
 * needs to find and appeal a rejection after they've navigated away.
 */
function statusBadge(video: VideoSummary): { variant: string; label: string } {
  switch (video.assetLifecycleState) {
    case 'REJECTED_RETAINED':
      return { variant: 'badge-danger', label: 'Rejected' };
    case 'QUARANTINED':
      return { variant: 'badge-warning', label: 'Under review' };
    case 'DELETE_SCHEDULED':
    case 'DELETION_IN_PROGRESS':
    case 'DELETED':
      return { variant: 'badge-danger', label: 'Removed' };
    case 'RESTORING':
      return { variant: 'badge-info', label: 'Restoring' };
    default:
      break;
  }
  switch (video.processingState) {
    case 'READY':
      return { variant: 'badge-success', label: 'Ready' };
    case 'FAILED':
      return { variant: 'badge-danger', label: 'Processing failed' };
    case 'EXPIRED':
      return { variant: 'badge-danger', label: 'Expired' };
    case 'TRANSCODING':
      return { variant: 'badge-warning', label: 'Transcoding' };
    default:
      return { variant: 'badge-info', label: 'Uploading' };
  }
}

export function MyVideos() {
  const [page, setPage] = useState(0);

  const list = useQuery({ queryKey: ['myVideos', page], queryFn: () => getMyVideos(page) });

  if (list.isPending) {
    return (
      <div className="status-line">
        <span className="spinner" style={{ width: 15, height: 15, borderWidth: 2 }} /> Loading your videos...
      </div>
    );
  }
  if (list.isError) {
    return <div className="status-line is-error">{(list.error as Error).message}</div>;
  }
  if (list.data.items.length === 0 && page === 0) {
    return <div className="status-line">You haven&apos;t uploaded any videos yet.</div>;
  }

  return (
    <div>
      {list.data.items.map((video) => (
        <MyVideoRow key={video.videoId} video={video} />
      ))}

      <div className="btn-row" style={{ marginTop: '1rem' }}>
        <button className="btn-ghost btn-sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
          Previous
        </button>
        <button className="btn-ghost btn-sm" disabled={!list.data.hasMore} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}

/**
 * One video the creator owns, with the actions they actually need on it.
 *
 * <p>This panel used to show a title, a raw UUID and a status badge, and nothing
 * else: no thumbnail, no counts, and no way to play, publish, unpublish or
 * delete. If you closed the upload sheet while a video was still transcoding,
 * this is where you landed — and there was no way to finish publishing from
 * here, so the publish flow existed only inside the sheet you had just closed.
 */
function MyVideoRow({ video }: { video: VideoSummary }) {
  const [expanded, setExpanded] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const queryClient = useQueryClient();

  const badge = statusBadge(video);
  const rejected = video.assetLifecycleState === 'REJECTED_RETAINED';
  const published = video.publicationState === 'PUBLISHED';
  const ready = video.processingState === 'READY';
  const gone =
    video.assetLifecycleState === 'DELETE_SCHEDULED' ||
    video.assetLifecycleState === 'DELETION_IN_PROGRESS' ||
    video.assetLifecycleState === 'DELETED';

  // Only meaningful for a published video; anything else has no public counts
  // worth a request.
  const counts = useQuery({
    queryKey: ['counts', video.videoId],
    queryFn: () => getVideoCounts(video.videoId),
    enabled: published,
    retry: false,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['myVideos'] });
    void queryClient.invalidateQueries({ queryKey: ['feed'] });
  };

  const publish = useMutation({ mutationFn: () => publishVideo(video.videoId), onSuccess: invalidate });
  const unpublish = useMutation({ mutationFn: () => unpublishVideo(video.videoId), onSuccess: invalidate });
  const remove = useMutation({ mutationFn: () => deleteVideo(video.videoId), onSuccess: invalidate });

  const busy = publish.isPending || unpublish.isPending || remove.isPending;
  const error = publish.error ?? unpublish.error ?? remove.error;

  return (
    <div className="my-video">
      <div className="my-video-main">
        <VideoThumb
          hit={{
            videoId: video.videoId,
            creatorId: '',
            creatorDisplayName: '',
            creatorHandle: '',
            title: video.title,
            description: null,
            publishedAt: video.createdAt,
          }}
        />
        <div className="my-video-body">
          <div className="my-video-title">{video.title || 'Untitled'}</div>
          <div className="my-video-meta">
            <span className={`badge ${badge.variant}`}>{badge.label}</span>
            {published && <span className="badge badge-success">Published</span>}
            {ready && !published && !gone && <span className="badge badge-neutral">Draft</span>}
            <span className="my-video-date" title={video.createdAt}>
              {relativeTime(video.createdAt)}
            </span>
          </div>
          {published && counts.data && (
            <div className="my-video-stats">
              <span>{formatCount(counts.data.viewCount)} views</span>
              <span>{formatCount(counts.data.likeCount)} likes</span>
              <span>{formatCount(counts.data.commentCount)} comments</span>
            </div>
          )}
        </div>
      </div>

      {!gone && (
        <div className="btn-row my-video-actions">
          {published && (
            <button className="btn-ghost btn-sm" onClick={() => navigate(videoPath(video.videoId))}>
              View
            </button>
          )}
          {ready && !published && (
            <button className="btn-primary btn-sm" disabled={busy} onClick={() => publish.mutate()}>
              {publish.isPending ? 'Publishing…' : 'Publish'}
            </button>
          )}
          {published && (
            <button className="btn-ghost btn-sm" disabled={busy} onClick={() => unpublish.mutate()}>
              {unpublish.isPending ? 'Unpublishing…' : 'Unpublish'}
            </button>
          )}
          {rejected && (
            <button className="btn-ghost btn-sm" onClick={() => setExpanded((v) => !v)}>
              {expanded ? 'Hide appeal' : 'View & appeal'}
            </button>
          )}
          {confirmingDelete ? (
            <>
              <button className="btn-danger-ghost btn-sm" disabled={busy} onClick={() => remove.mutate()}>
                {remove.isPending ? 'Deleting…' : 'Delete permanently'}
              </button>
              <button className="btn-ghost btn-sm" onClick={() => setConfirmingDelete(false)}>
                Cancel
              </button>
            </>
          ) : (
            <button className="btn-ghost btn-sm" onClick={() => setConfirmingDelete(true)}>
              Delete
            </button>
          )}
        </div>
      )}

      {confirmingDelete && !remove.isPending && (
        <p className="my-video-warning">
          This removes the video and its files for good. It can&apos;t be undone.
        </p>
      )}

      {error && <div className="status-line is-error">{(error as Error).message}</div>}
      {rejected && expanded && <AppealSection videoId={video.videoId} />}
    </div>
  );
}

const APPEAL_STATUS_TEXT: Record<AppealResponse['state'], string> = {
  NONE: '',
  UNDER_APPEAL: 'Appeal submitted; awaiting admin review.',
  REVIEWING: 'Your appeal is being reviewed.',
  APPROVED: 'Your appeal was approved. The video will be reinstated.',
  DENIED: 'Your appeal was denied. You may explain further and resubmit.',
  ESCALATED: 'Your appeal has been escalated for further review.',
};

function AppealSection({ videoId }: { videoId: string }) {
  const [reason, setReason] = useState('');
  const queryClient = useQueryClient();

  const status = useQuery({ queryKey: ['appealStatus', videoId], queryFn: () => getAppealStatus(videoId) });

  const appeal = useMutation({
    mutationFn: () => submitAppeal(videoId, reason),
    onSuccess: (response) => {
      queryClient.setQueryData(['appealStatus', videoId], response);
      setReason('');
    },
  });

  if (status.isPending) {
    return (
      <div className="status-line" style={{ marginTop: '0.6rem' }}>
        Loading appeal status...
      </div>
    );
  }
  if (status.isError) {
    return (
      <div className="status-line is-error" style={{ marginTop: '0.6rem' }}>
        {(status.error as Error).message}
      </div>
    );
  }

  const current = appeal.data ?? status.data;
  const canSubmit = current.state === 'NONE' || current.state === 'DENIED';

  return (
    <div className="callout callout-warning" style={{ marginTop: '0.6rem' }}>
      {current.state !== 'NONE' && (
        <div className="callout-title">
          <CheckIcon /> {APPEAL_STATUS_TEXT[current.state]}
        </div>
      )}
      {current.decisionReason && <p style={{ marginTop: 0 }}>Admin note: {current.decisionReason}</p>}

      {canSubmit && (
        <>
          <div className="callout-title">
            <FlagIcon /> {current.state === 'DENIED' ? 'Resubmit your appeal' : 'This video was rejected by moderation'}
          </div>
          <p>If you believe this was a mistake, explain why below.</p>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Explain why this decision should be reconsidered..."
            rows={3}
            style={{ marginTop: '0.5rem' }}
          />
          <button
            className="btn-primary btn-sm"
            style={{ marginTop: '0.6rem' }}
            disabled={!reason.trim() || appeal.isPending}
            onClick={() => appeal.mutate()}
          >
            {appeal.isPending ? 'Submitting...' : 'Submit appeal'}
          </button>
          {appeal.isError && <div className="status-line is-error">{(appeal.error as Error).message}</div>}
        </>
      )}
    </div>
  );
}
