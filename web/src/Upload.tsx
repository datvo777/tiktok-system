import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Hls from 'hls.js';
import { useEffect, useRef, useState } from 'react';
import {
  completeUpload,
  createPreviewSession,
  createUpload,
  getVideo,
  publishVideo,
  postToPresignedUrl,
  submitAppeal,
  type AppealResponse,
  type PublicationResponse,
  type VideoResponse,
} from './api';

// Brief section 12.3: back off from 1s to a 10s ceiling if the server gives no
// hint, and give up after 10 minutes rather than polling forever.
const DEFAULT_POLL_MS = 2000;
const MAX_POLL_MS = 10_000;
const GIVE_UP_AFTER_MS = 10 * 60 * 1000;
// Once processing reaches READY, a moderation decision can still land moments
// later (or minutes later, on appeal review) and change assetLifecycleState —
// keep a slower background poll alive instead of stopping outright, so a
// rejection or reinstatement is reflected without a manual page reload.
const POST_READY_POLL_MS = 5000;

// The media worker's ffmpeg step detects the real container from the file's
// bytes, not its extension or a client-supplied MIME type — so it already
// accepts far more than MP4. This list is a deliberate, tested allowlist
// (not "whatever ffmpeg happens to decode"), covering what people actually
// export from a Mac: QuickTime's native .mov, iTunes/Apple's .m4v, and the
// common web/legacy formats alongside .mp4 itself.
const ACCEPTED_VIDEO_TYPES: Record<string, string[]> = {
  '.mp4': ['video/mp4'],
  '.mov': ['video/quicktime'],
  '.m4v': ['video/x-m4v', 'video/mp4'],
  '.webm': ['video/webm'],
  '.avi': ['video/x-msvideo'],
  '.mkv': ['video/x-matroska'],
};
const ACCEPT_ATTR = Object.keys(ACCEPTED_VIDEO_TYPES)
  .concat(...Object.values(ACCEPTED_VIDEO_TYPES))
  .join(',');

// Belt-and-suspenders on top of the file input's `accept` filter: some
// browser/OS combinations report an empty or generic MIME type for a picked
// file, so fall back to the extension rather than trust `type` alone.
function isAcceptedVideo(file: File): boolean {
  const name = file.name.toLowerCase();
  return Object.entries(ACCEPTED_VIDEO_TYPES).some(
    ([ext, mimeTypes]) => name.endsWith(ext) || mimeTypes.includes(file.type),
  );
}

const STATE_BADGE: Record<string, { variant: string; label: string }> = {
  CREATED: { variant: 'badge-neutral', label: 'Created' },
  UPLOADING: { variant: 'badge-info', label: 'Uploading' },
  UPLOADED: { variant: 'badge-info', label: 'Uploaded' },
  TRANSCODING: { variant: 'badge-warning', label: 'Transcoding' },
  READY: { variant: 'badge-success', label: 'Ready' },
  FAILED: { variant: 'badge-danger', label: 'Failed' },
  EXPIRED: { variant: 'badge-danger', label: 'Expired' },
};

export function Upload() {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [videoId, setVideoId] = useState<string | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [log, setLog] = useState('Pick a video and upload it.');
  const queryClient = useQueryClient();

  const upload = useMutation({
    mutationFn: async ({ selected, title, description }: { selected: File; title: string; description: string }) => {
      const session = await createUpload(title, description);
      // Checked before spending the upload: the policy caps the body server-side
      // too, but failing here explains why instead of surfacing EntityTooLarge.
      if (selected.size > session.maxBytes) {
        throw new Error(
          `That file is ${(selected.size / 1_048_576).toFixed(0)} MB; the limit is ` +
            `${(session.maxBytes / 1_048_576).toFixed(0)} MB.`,
        );
      }
      await postToPresignedUrl(session, selected);
      await completeUpload(session.uploadId);
      return session.videoId;
    },
    onMutate: () => setLog('Creating upload session...'),
    onSuccess: (newVideoId) => {
      setVideoId(newVideoId);
      setStartedAt(Date.now());
      setLog(`Uploaded. Polling ${newVideoId} for processing status...`);
    },
    onError: (error) => setLog(`Upload failed: ${(error as Error).message}`),
  });

  const status = useQuery<VideoResponse>({
    queryKey: ['video', videoId],
    queryFn: () => getVideo(videoId as string),
    enabled: videoId !== null,
    refetchInterval: (query) => {
      if (!startedAt || Date.now() - startedAt > GIVE_UP_AFTER_MS) return false;
      const data = query.state.data;
      if (!data || data.processingState === 'FAILED') return false;
      if (data.processingState === 'READY') return POST_READY_POLL_MS;
      return Math.min(data.pollAfterMs ?? DEFAULT_POLL_MS, MAX_POLL_MS);
    },
  });

  useEffect(() => {
    if (status.data?.processingState === 'FAILED') {
      setLog(`Processing failed: ${status.data.failureClass ?? 'unknown'}.`);
    } else if (status.data?.processingState === 'READY') {
      setLog('Ready. Click Preview to play it back through the media gateway.');
    }
  }, [status.data?.processingState, status.data?.failureClass]);

  const badge = status.data ? STATE_BADGE[status.data.processingState] : null;

  return (
    <section className="card">
      <div className="card-head">
        <h2>Upload &amp; preview</h2>
        <span className="card-eyebrow">Milestone 2</span>
      </div>
      <p className="card-desc">
        Direct-to-MinIO upload, FFmpeg transcode via the media worker, and owner-preview playback through the Spring
        media gateway.
      </p>

      <label className="field">
        <span className="field-label">Title</span>
        <input
          placeholder="Give it a title"
          value={title}
          maxLength={150}
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>
      <label className="field">
        <span className="field-label">Description (optional)</span>
        <textarea
          placeholder="What's this video about?"
          value={description}
          maxLength={2000}
          rows={2}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>

      <div className="btn-row">
        <input
          type="file"
          accept={ACCEPT_ATTR}
          onChange={(e) => {
            const selected = e.target.files?.[0] ?? null;
            if (selected && !isAcceptedVideo(selected)) {
              setFile(null);
              setLog(
                `"${selected.name}" isn't a supported video file. Pick one of: ${Object.keys(ACCEPTED_VIDEO_TYPES).join(', ')}.`,
              );
              e.target.value = '';
              return;
            }
            setFile(selected);
            if (selected) setLog(`Picked ${selected.name}.`);
          }}
        />
        <button
          className="btn-primary"
          disabled={!file || !title.trim() || upload.isPending}
          onClick={() => {
            if (file) upload.mutate({ selected: file, title: title.trim(), description: description.trim() });
          }}
        >
          {upload.isPending ? 'Uploading...' : 'Upload'}
        </button>
      </div>

      {videoId && (
        <div className="meta-line">
          {videoId}
          {badge && (
            <>
              {' '}
              <span className={`badge ${badge.variant}`}>{badge.label}</span>
            </>
          )}
          {status.data?.processingVersion != null ? ` · v${status.data.processingVersion}` : ''}
        </div>
      )}

      <div className="log-panel">{log}</div>

      {videoId && status.data?.processingState === 'READY' && (
        <>
          <Preview videoId={videoId} onLog={setLog} />
          <PublishButton videoId={videoId} onLog={setLog} />
        </>
      )}

      {videoId && status.data?.assetLifecycleState === 'REJECTED_RETAINED' && (
        <AppealPanel videoId={videoId} onLog={setLog} />
      )}

      <button
        className="btn-ghost btn-sm"
        style={{ marginTop: '0.75rem' }}
        onClick={() => {
          setVideoId(null);
          setStartedAt(null);
          setFile(null);
          setTitle('');
          setDescription('');
          void queryClient.invalidateQueries({ queryKey: ['video'] });
        }}
      >
        Reset
      </button>
    </section>
  );
}

function AppealPanel({ videoId, onLog }: { videoId: string; onLog: (message: string) => void }) {
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<AppealResponse | null>(null);

  const appeal = useMutation({
    mutationFn: () => submitAppeal(videoId, reason),
    onMutate: () => onLog('Submitting appeal...'),
    onSuccess: (response) => {
      setResult(response);
      onLog('Appeal submitted; awaiting admin review.');
    },
    onError: (error) => onLog(`Appeal failed: ${(error as Error).message}`),
  });

  if (result) {
    return (
      <div className="callout callout-warning">
        <div className="callout-title">
          ⚑ Appeal {result.state === 'UNDER_APPEAL' ? 'submitted' : result.state.toLowerCase()}
        </div>
      </div>
    );
  }

  return (
    <div className="callout callout-warning">
      <div className="callout-title">⚑ This video was rejected by moderation</div>
      <p>If you believe this was a mistake, you may appeal the decision.</p>
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
    </div>
  );
}

function Preview({ videoId, onLog }: { videoId: string; onLog: (message: string) => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);

  // The element is captured while the effect runs, not read at cleanup time:
  // React detaches refs during the commit phase, before passive effect cleanups
  // are flushed, so `videoRef.current` is already null by then and detachHls was
  // silently doing nothing — leaving every hls.js instance alive with its
  // MediaSource, segment loaders and retry timers still running.
  useEffect(() => {
    const element = videoRef.current;
    return () => detachHls(element);
  }, []);

  const session = useMutation({
    mutationFn: () => createPreviewSession(videoId),
    onMutate: () => onLog('Requesting preview session...'),
    onSuccess: (result) => {
      onLog(`Preview session issued (expires ${result.expiresAt}). Attaching player...`);
      attachHls(videoRef.current, videoId, result.processingVersion, onLog);
    },
    onError: (error) => onLog(`Preview session failed: ${(error as Error).message}`),
  });

  return (
    <div style={{ marginTop: '1rem' }}>
      <button className="btn-sm" onClick={() => session.mutate()} disabled={session.isPending}>
        {session.isPending ? 'Requesting session...' : '▶ Preview'}
      </button>
      <video
        ref={videoRef}
        controls
        style={{
          display: 'block',
          marginTop: '0.6rem',
          maxWidth: '260px',
          borderRadius: 'var(--radius-md)',
          background: '#0d0d14',
        }}
      />
    </div>
  );
}

// Moderation can approve (or a rejection/appeal can flip the decision) any time
// after the initial publish click, on a completely separate admin screen with no
// way to signal this tab. `requestPublish` on the server is idempotent by design
// (repeating it after the state already changed returns the current view instead
// of re-appending an event), so it doubles safely as a status poll here rather
// than needing a second read endpoint -- otherwise this badge would freeze on
// whatever state the first response happened to catch, e.g. PUBLISH_PENDING,
// forever, even once the video is actually live.
const PUBLISH_POLL_MS = 3000;

function PublishButton({ videoId, onLog }: { videoId: string; onLog: (message: string) => void }) {
  const [requested, setRequested] = useState(false);

  const status = useQuery<PublicationResponse>({
    queryKey: ['publication', videoId],
    queryFn: () => publishVideo(videoId),
    enabled: requested,
    refetchInterval: (query) => (query.state.data?.state === 'PUBLISH_PENDING' ? PUBLISH_POLL_MS : false),
  });

  useEffect(() => {
    if (status.isError) {
      onLog(`Publish failed: ${(status.error as Error).message}`);
    } else if (status.data) {
      onLog(
        status.data.state === 'PUBLISHED'
          ? 'Published — visible in the public feed.'
          : `Publication intent recorded; state is ${status.data.state} until moderation approves it.`,
      );
    }
    // onLog is a fresh closure every render; only re-run when the status itself changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status.data?.state, status.isError]);

  return (
    <div className="btn-row" style={{ marginTop: '0.6rem' }}>
      <button
        className="btn-primary btn-sm"
        onClick={() => setRequested(true)}
        disabled={requested && status.isFetching && !status.data}
      >
        {requested && status.isFetching && !status.data ? 'Publishing...' : 'Publish'}
      </button>
      {status.data && (
        <span
          className={`badge ${status.data.state === 'PUBLISHED' ? 'badge-success' : 'badge-warning'}`}
          style={{ textTransform: 'none' }}
        >
          {status.data.state}
        </span>
      )}
    </div>
  );
}

// hls.js attaches a MediaSource to the <video> element. Destroying one
// instance and immediately attaching a *new* MediaSource to the same element
// is a known race in some browsers — the old one isn't always fully released
// before the new attach, which is exactly what "mediaSourceRequiresReset"
// means. Reuse one instance per element across repeated Preview/Play clicks
// (loadSource() again instead of destroy()+new Hls()) to sidestep the race
// rather than try to win it.
const activeHlsByElement = new WeakMap<HTMLVideoElement, Hls>();

/**
 * Shared by owner preview and public feed playback — both are just an HLS URL
 * once the right session cookie has been set (brief section 8).
 */
export function attachHls(
  video: HTMLVideoElement | null,
  videoId: string,
  processingVersion: number,
  onLog: (message: string) => void,
) {
  const url = `/media/videos/${videoId}/${processingVersion}/master.m3u8`;
  if (!video) return;

  if (Hls.isSupported()) {
    let hls = activeHlsByElement.get(video);
    if (!hls) {
      hls = new Hls();
      activeHlsByElement.set(video, hls);
      hls.attachMedia(video);
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) onLog(`Playback error: ${data.type} — ${data.details}`);
      });
    }
    hls.loadSource(url);
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari's native HLS: no headers on this request either (Rule 17), the
    // cookies set moments ago are what authorize it.
    video.src = url;
  } else {
    onLog('This browser supports neither MSE (hls.js) nor native HLS playback.');
  }
}

/** Tears down any hls.js instance attached to this element, e.g. before it unmounts. */
export function detachHls(video: HTMLVideoElement | null) {
  if (!video) return;
  activeHlsByElement.get(video)?.destroy();
  activeHlsByElement.delete(video);
}
