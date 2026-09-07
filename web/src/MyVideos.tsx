import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { CheckIcon, FlagIcon } from './icons';
import { type AppealResponse, type VideoSummary, getAppealStatus, getMyVideos, submitAppeal } from './api';

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
  const [expanded, setExpanded] = useState<string | null>(null);

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
      {list.data.items.map((video) => {
        const badge = statusBadge(video);
        const rejected = video.assetLifecycleState === 'REJECTED_RETAINED';
        const isOpen = expanded === video.videoId;
        return (
          <div key={video.videoId} className="callout" style={{ marginBottom: '0.75rem' }}>
            <div className="btn-row" style={{ justifyContent: 'space-between' }}>
              <div>
                <div style={{ fontWeight: 600 }}>{video.title || 'Untitled'}</div>
                <div className="mono" style={{ fontSize: '0.75rem' }}>
                  {video.videoId}
                </div>
              </div>
              <span className={`badge ${badge.variant}`}>{badge.label}</span>
            </div>

            {rejected && (
              <button
                className="btn-ghost btn-sm"
                style={{ marginTop: '0.6rem' }}
                onClick={() => setExpanded(isOpen ? null : video.videoId)}
              >
                {isOpen ? 'Hide appeal' : 'View & appeal'}
              </button>
            )}

            {rejected && isOpen && <AppealSection videoId={video.videoId} />}
          </div>
        );
      })}

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
