import type { VideoSummary } from './api';

export const LIST_POLL_MS = 5000;
/**
 * While the realtime stream is up, a change announces itself (the hint refetches the list at once), so
 * the poll is only a safety net for a hint that never came. Measured at 4,000 users with 10% waiting on
 * an upload: the status polls were 200 of the 289 requests per second, and relaxing them took the
 * total to 116 req/s and the backend CPU from 0.42 to 0.26 cores.
 */
export const SAFETY_NET_POLL_MS = 15_000;

export function listPollMs(streamConnected: boolean): number {
  return streamConnected ? SAFETY_NET_POLL_MS : LIST_POLL_MS;
}

/**
 * A draft that never got its file sits in CREATED until the cleanup job expires it. Polling for
 * that whole time would keep a tab busy for something that is never going to change, so only a
 * recent one counts as in flight.
 */
const IN_FLIGHT_MAX_AGE_MS = 60 * 60 * 1000;

/** Whether anything in the list can still change on its own (processing or moderation under way). */
export function hasVideoInFlight(videos: readonly VideoSummary[], now = Date.now()): boolean {
  return videos.some((video) => {
    const removed =
      video.assetLifecycleState === 'DELETE_SCHEDULED' ||
      video.assetLifecycleState === 'DELETION_IN_PROGRESS' ||
      video.assetLifecycleState === 'DELETED';
    if (removed) return false;
    if (video.publicationState === 'PUBLISH_PENDING') return true;
    const processing =
      video.processingState === 'CREATED' ||
      video.processingState === 'UPLOADING' ||
      video.processingState === 'UPLOADED' ||
      video.processingState === 'TRANSCODING';
    return processing && now - Date.parse(video.createdAt) < IN_FLIGHT_MAX_AGE_MS;
  });
}

export function statusBadge(video: VideoSummary): { variant: string; label: string } {
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
    case 'CREATED':
      // A draft exists but nothing has processed it: the file may still be on its way, or the
      // upload finished and processing has not been picked up yet. "Uploading" claimed the former
      // for a file that had long since arrived.
      return { variant: 'badge-neutral', label: 'Waiting to process' };
    default:
      return { variant: 'badge-info', label: 'Uploading' };
  }
}

const DEFAULT_POLL_MS = 2000;
const MAX_POLL_MS = 10_000;

/**
 * How long to wait before asking again about one video. The server's hint is a constant 2s for
 * every in-flight state, so on its own the poll never slows down however long a transcode takes.
 * Backing off with elapsed time (brief section 12.3: up to a 10s ceiling) keeps a long wait from
 * costing a request every two seconds, while the first minute, when most videos finish, stays quick.
 */
export function pollDelayMs(
  hintMs: number | null | undefined,
  elapsedMs: number,
  streamConnected = false,
): number {
  if (streamConnected) return SAFETY_NET_POLL_MS;
  const base = hintMs ?? DEFAULT_POLL_MS;
  const factor = elapsedMs > 3 * 60_000 ? 5 : elapsedMs > 60_000 ? 2 : 1;
  return Math.min(base * factor, MAX_POLL_MS);
}
