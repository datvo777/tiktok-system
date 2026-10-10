import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Hls from 'hls.js';
import { useEffect, useRef, useState } from 'react';
import { useRealtimeConnected } from './realtime';
import { pollDelayMs } from './videoStatus';
import { CheckIcon, FlagIcon, PlayIcon, UploadCloudIcon } from './icons';
import {
  completeUploadWithRetry,
  isRetryableCompleteError,
  createPreviewSession,
  createUpload,
  getVideo,
  publishVideo,
  postToPresignedUrl,
  submitAppeal,
  UploadCancelled,
  type AppealResponse,
  type PublicationResponse,
  type VideoResponse,
} from './api';

// Brief section 12.3: back off toward a 10s ceiling (see pollDelayMs), and give up
// after 10 minutes rather than polling forever.
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

/** `complete` kept failing transiently after the file was stored; carries the last error. */
class FinishFailed extends Error {
  constructor(readonly lastError: Error) {
    super(lastError.message);
  }
}

export function Upload({ onDone }: { onDone?: (() => void) | undefined } = {}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [videoId, setVideoId] = useState<string | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [log, setLog] = useState('Pick a video and upload it.');
  /** 0..1 while bytes are moving; null before and after. */
  const [progress, setProgress] = useState<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /**
   * Set once the bytes are in storage and cleared when `complete` succeeds. While
   * it is set, a retry must only call `complete` again: the file is already there,
   * and starting over would create another session and draft and re-send the file.
   */
  const [stored, setStored] = useState<{ uploadId: string; videoId: string } | null>(null);
  const queryClient = useQueryClient();
  const streamConnected = useRealtimeConnected();

  const upload = useMutation({
    mutationFn: async ({ selected, title, description }: { selected: File; title: string; description: string }) => {
      const controller = new AbortController();
      abortRef.current = controller;

      let session = stored;
      if (!session) {
        const created = await createUpload(title, description, selected.size);
        // Checked before spending the upload: the policy caps the body server-side
        // too, but failing here explains why instead of surfacing EntityTooLarge.
        if (selected.size > created.maxBytes) {
          throw new Error(
            `That file is ${(selected.size / 1_048_576).toFixed(0)} MB; the limit is ` +
              `${(created.maxBytes / 1_048_576).toFixed(0)} MB.`,
          );
        }
        await postToPresignedUrl(created, selected, {
          onProgress: setProgress,
          signal: controller.signal,
        });
        session = { uploadId: created.uploadId, videoId: created.videoId };
        setStored(session);
      }
      setProgress(null);
      try {
        await completeUploadWithRetry(session.uploadId, controller.signal);
      } catch (error) {
        // The session is gone (expired, object missing, not allowed): resuming it
        // cannot work, so fall back to a fresh upload on the next attempt.
        const resumable = isRetryableCompleteError(error) || error instanceof UploadCancelled;
        if (!resumable) setStored(null);
        throw resumable && !(error instanceof UploadCancelled) ? new FinishFailed(error as Error) : error;
      }
      setStored(null);
      return session.videoId;
    },
    onMutate: () => {
      if (stored) {
        setProgress(null);
        setLog('Finishing your upload…');
      } else {
        setProgress(0);
        setLog('Getting your upload ready…');
      }
    },
    onSuccess: (newVideoId) => {
      setVideoId(newVideoId);
      setStartedAt(Date.now());
      setProgress(null);
      // No identifiers in copy the uploader reads: what they need to know is
      // that the file arrived and something is happening to it, not the id the
      // client is polling.
      setLog('Uploaded. Preparing your video…');
    },
    onError: (error) => {
      setProgress(null);
      if (error instanceof UploadCancelled) {
        setLog('Upload cancelled.');
      } else if (error instanceof FinishFailed) {
        // The file is stored; the button now resumes from `complete`.
        setLog(`Your file is uploaded, but we couldn't finish processing it (${error.lastError.message}). Try again.`);
      } else {
        setLog(`Upload failed: ${(error as Error).message}`);
      }
    },
    onSettled: () => {
      abortRef.current = null;
    },
  });

  // A navigation away mid-upload should stop the transfer, not leave it running
  // against a component that is gone.
  useEffect(() => () => abortRef.current?.abort(), []);

  const status = useQuery<VideoResponse>({
    queryKey: ['video', videoId],
    queryFn: () => getVideo(videoId as string),
    enabled: videoId !== null,
    refetchInterval: (query) => {
      if (!startedAt || Date.now() - startedAt > GIVE_UP_AFTER_MS) return false;
      const data = query.state.data;
      if (!data || data.processingState === 'FAILED') return false;
      if (data.processingState === 'READY') return POST_READY_POLL_MS;
      return pollDelayMs(data.pollAfterMs, Date.now() - startedAt, streamConnected);
    },
  });

  useEffect(() => {
    if (status.data?.processingState === 'FAILED') {
      // failureClass is an internal taxonomy (TERMINAL/TRANSIENT and friends);
      // what the uploader needs is whether trying again is worth their time.
      setLog(
        status.data.failureClass === 'TRANSIENT'
          ? "We couldn't process that video. Try uploading it again."
          : "We couldn't process that video. Check that it plays locally, then try a different file.",
      );
    } else if (status.data?.processingState === 'READY') {
      setLog('Ready. Preview it below, then publish when you\u2019re happy with it.');
    }
  }, [status.data?.processingState, status.data?.failureClass]);

  const badge = status.data ? STATE_BADGE[status.data.processingState] : null;
  const isFailure = upload.isError || status.data?.processingState === 'FAILED';

  // Shared by the file input and the drop target: the same validation has to
  // run either way, since a dropped file never passes through `accept`.
  function pick(selected: File | null) {
    if (selected && !isAcceptedVideo(selected)) {
      setFile(null);
      setLog(
        `"${selected.name}" isn't a supported video file. Pick one of: ${Object.keys(ACCEPTED_VIDEO_TYPES).join(', ')}.`,
      );
      return;
    }
    setFile(selected);
    if (selected) setLog(`Ready to upload ${selected.name}.`);
  }

  return (
    <div>
      <FilePicker file={file} onPick={pick} />

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

      {upload.isPending ? (
        <div className="upload-progress">
          <div
            className="upload-progress-track"
            role="progressbar"
            aria-label="Upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            {...(progress !== null ? { 'aria-valuenow': Math.round(progress * 100) } : {})}
          >
            <span
              className={`upload-progress-fill${progress === null ? ' is-indeterminate' : ''}`}
              style={progress === null ? undefined : { transform: `scaleX(${progress})` }}
            />
          </div>
          <div className="upload-progress-foot">
            <span>
              {progress === null
                ? 'Finishing up…'
                : `${Math.round(progress * 100)}%${
                    file ? ` of ${(file.size / 1_048_576).toFixed(1)} MB` : ''
                  }`}
            </span>
            <button className="btn-ghost btn-sm" onClick={() => abortRef.current?.abort()}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <button
          className="btn-primary btn-block"
          disabled={!file || !title.trim()}
          onClick={() => {
            if (file) upload.mutate({ selected: file, title: title.trim(), description: description.trim() });
          }}
        >
          {stored ? 'Finish upload' : 'Upload'}
        </button>
      )}

      {videoId && badge && (
        <div className="upload-meta">
          <span className={`badge ${badge.variant}`}>{badge.label}</span>
        </div>
      )}

      <div className={`status-line${isFailure ? ' is-error' : ''}`}>{log}</div>

      {videoId && status.data?.processingState === 'READY' && (
        <>
          <div className="step-divider">Preview</div>
          <Preview videoId={videoId} onLog={setLog} />
          <div className="step-divider">Publish</div>
          <PublishButton videoId={videoId} onLog={setLog} onDone={onDone} />
        </>
      )}

      {videoId && status.data?.assetLifecycleState === 'REJECTED_RETAINED' && (
        <AppealPanel videoId={videoId} onLog={setLog} />
      )}

      <button
        className="btn-ghost btn-sm"
        style={{ marginTop: '1rem' }}
        onClick={() => {
          setVideoId(null);
          setStartedAt(null);
          setStored(null);
          setFile(null);
          setTitle('');
          setDescription('');
          void queryClient.invalidateQueries({ queryKey: ['video'] });
        }}
      >
        Reset
      </button>
    </div>
  );
}

/**
 * The bare `<input type="file">` was the one piece of unstyled browser chrome
 * left in the app. This wraps it in a real drop target -- the input is still
 * the thing that opens the picker, it just isn't what you look at.
 */
function FilePicker({ file, onPick }: { file: File | null; onPick: (file: File | null) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  return (
    <button
      type="button"
      className={`dropzone${dragging ? ' dragging' : ''}${file ? ' has-file' : ''}`}
      onClick={() => inputRef.current?.click()}
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        onPick(e.dataTransfer.files?.[0] ?? null);
      }}
    >
      {file ? <CheckIcon /> : <UploadCloudIcon />}
      <span>
        <span className="dropzone-title">{file ? file.name : 'Select or drop a video'}</span>
        <span className="dropzone-hint">
          {file
            ? `${(file.size / 1_048_576).toFixed(1)} MB · click to change`
            : Object.keys(ACCEPTED_VIDEO_TYPES).join('  ')}
        </span>
      </span>
      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT_ATTR}
        hidden
        onChange={(e) => {
          onPick(e.target.files?.[0] ?? null);
          // Cleared so re-picking the same file after a rejection still fires.
          e.target.value = '';
        }}
      />
    </button>
  );
}

function AppealPanel({ videoId, onLog }: { videoId: string; onLog: (message: string) => void }) {
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<AppealResponse | null>(null);

  const appeal = useMutation({
    mutationFn: () => submitAppeal(videoId, reason),
    onMutate: () => onLog('Sending your appeal\u2026'),
    onSuccess: (response) => {
      setResult(response);
      onLog("Appeal sent. We'll let you know in your Inbox once it's reviewed.");
    },
    onError: (error) => onLog(`Couldn't send that appeal: ${(error as Error).message}`),
  });

  if (result) {
    return (
      <div className="callout callout-warning">
        <div className="callout-title">
          <CheckIcon /> Appeal {result.state === 'UNDER_APPEAL' ? 'submitted' : result.state.toLowerCase()}
        </div>
      </div>
    );
  }

  return (
    <div className="callout callout-warning">
      <div className="callout-title">
        <FlagIcon /> This video was rejected by moderation
      </div>
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
  // Unknown until the browser reads the stream's actual dimensions -- guessing
  // portrait up front made a landscape upload render squashed into a 9:16 box.
  const [orientation, setOrientation] = useState<'portrait' | 'landscape' | null>(null);

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
    onMutate: () => onLog('Loading your preview\u2026'),
    onSuccess: (result) => {
      onLog('Playing your preview.');
      attachHls(videoRef.current, videoId, result.processingVersion, onLog, () =>
        createPreviewSession(videoId),
      );
    },
    onError: (error) => onLog(`Couldn't load the preview: ${(error as Error).message}`),
  });

  return (
    <div>
      <button className="btn-sm btn-row" onClick={() => session.mutate()} disabled={session.isPending}>
        <PlayIcon size={14} />
        {session.isPending ? 'Requesting session…' : 'Play preview'}
      </button>
      <video
        ref={videoRef}
        controls
        className={`preview-video${orientation === 'landscape' ? ' preview-video-landscape' : ''}`}
        onLoadedMetadata={(e) => {
          const { videoWidth, videoHeight } = e.currentTarget;
          if (videoWidth && videoHeight) setOrientation(videoWidth >= videoHeight ? 'landscape' : 'portrait');
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

/** Short badge wording per publication state; the callout below carries the detail. */
const PUBLICATION_LABEL: Record<string, string> = {
  PUBLISHED: 'Published',
  PUBLISH_PENDING: 'In review',
  SUSPENDED: 'Suspended',
  PRIVATE: 'Private',
  REMOVED: 'Removed',
};

/** What to tell the uploader, and whether there's still anything for them to wait on here. */
const PUBLICATION_GUIDANCE: Record<string, { text: string; settled: boolean }> = {
  PUBLISHED: { text: 'Published — visible in the public feed now.', settled: true },
  PUBLISH_PENDING: {
    text: "Waiting on moderation review, usually a few minutes. We'll notify your Inbox once it's decided — feel free to close this.",
    settled: false,
  },
  SUSPENDED: { text: 'This video was suspended and is not visible in the feed.', settled: true },
  PRIVATE: { text: 'This video is private.', settled: true },
  REMOVED: { text: 'This video has been removed.', settled: true },
};

function PublishButton({
  videoId,
  onLog,
  onDone,
}: {
  videoId: string;
  onLog: (message: string) => void;
  onDone?: (() => void) | undefined;
}) {
  const [requested, setRequested] = useState(false);
  const queryClient = useQueryClient();

  const status = useQuery<PublicationResponse>({
    queryKey: ['publication', videoId],
    queryFn: () => publishVideo(videoId),
    enabled: requested,
    refetchInterval: (query) => (query.state.data?.state === 'PUBLISH_PENDING' ? PUBLISH_POLL_MS : false),
  });

  useEffect(() => {
    if (status.isError) {
      onLog(`Couldn't publish that: ${(status.error as Error).message}`);
    } else if (status.data) {
      // A raw state code is not an explanation; PUBLICATION_GUIDANCE below says
      // what each state actually means for the person waiting.
      onLog(
        status.data.state === 'PUBLISHED'
          ? 'Published \u2014 it\u2019s in the feed now.'
          : (PUBLICATION_GUIDANCE[status.data.state]?.text ?? 'Waiting on review.'),
      );
      // The Feed stays mounted behind this modal the whole time, so its
      // ['feed'] query never remounts to pick up the new video on its own —
      // without this it would sit invisible until something else (a window
      // focus, a manual reload) happened to trigger a refetch.
      if (status.data.state === 'PUBLISHED') {
        void queryClient.invalidateQueries({ queryKey: ['feed'] });
      }
    }
    // onLog and queryClient are fresh/stable across renders; only re-run when the status itself changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status.data?.state, status.isError]);

  const guidance = status.data ? PUBLICATION_GUIDANCE[status.data.state] : undefined;

  return (
    <div>
      <div className="btn-row">
        <button
          className="btn-primary btn-sm"
          onClick={() => setRequested(true)}
          disabled={requested && status.isFetching && !status.data}
        >
          {requested && status.isFetching && !status.data ? 'Publishing...' : 'Publish'}
        </button>
        {status.data && (
          <span className={`badge ${status.data.state === 'PUBLISHED' ? 'badge-success' : 'badge-warning'}`}>
            {PUBLICATION_LABEL[status.data.state] ?? 'In review'}
          </span>
        )}
      </div>

      {/* Once a publish is recorded, the uploader is stuck in this modal with only a
          state code to go on -- spell out what happens next and give them a way out
          instead of leaving them staring at "PUBLISH_PENDING". */}
      {guidance && (
        <div className={`callout${guidance.settled ? '' : ' callout-warning'}`} style={{ marginTop: '0.85rem' }}>
          <p style={{ marginTop: 0 }}>{guidance.text}</p>
          {onDone && (
            <button className="btn-ghost btn-sm" style={{ marginTop: '0.6rem' }} onClick={onDone}>
              Done
            </button>
          )}
        </div>
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
// Tracks whether a native-HLS (Safari) element already has its recovery
// listeners attached, the same way activeHlsByElement tracks the hls.js
// instance -- attachHls is called again on every renewed session, and a
// fresh addEventListener each time would pile up duplicate listeners.
const nativeHlsRecoveryByElement = new WeakSet<HTMLVideoElement>();

/**
 * Shared by owner preview and public feed playback — both are just an HLS URL
 * once the right session cookie has been set (brief section 8).
 *
 * <p>The playback session cookie is short-lived (300s, brief section 8) and
 * nothing renews it on its own -- a video watched past that point, or resumed
 * after a long pause, fails with a stale cookie. `renewSession`, when given,
 * is called to request a fresh session and retried once; without it (or once
 * that one retry has also failed) the failure is reported as before. This is
 * the reactive half of the fix -- the proactive half is checking the
 * session's `expiresAt` before a deliberate resume, which the caller does
 * itself since only it knows when that is.
 */
export function attachHls(
  video: HTMLVideoElement | null,
  videoId: string,
  processingVersion: number,
  onLog: (message: string) => void,
  renewSession?: () => Promise<unknown>,
) {
  const url = `/media/videos/${videoId}/${processingVersion}/master.m3u8`;
  if (!video) return;

  if (Hls.isSupported()) {
    let hls = activeHlsByElement.get(video);
    if (!hls) {
      hls = new Hls();
      activeHlsByElement.set(video, hls);
      hls.attachMedia(video);

      // Cleared on every fragment that actually loads, so a *later* expiry
      // still gets its own retry. Left set across a renew attempt that is
      // immediately followed by another auth failure -- with no successful
      // load in between -- so a session that is rejected for a reason other
      // than expiry (the video lost eligibility, the account was banned)
      // fails once instead of retrying forever.
      let awaitingRecovery = false;
      const instance = hls;
      instance.on(Hls.Events.FRAG_LOADED, () => {
        awaitingRecovery = false;
      });
      instance.on(Hls.Events.ERROR, (_event, data) => {
        const authFailure = data.response?.code === 401 || data.response?.code === 403;
        if (authFailure && renewSession && !awaitingRecovery) {
          awaitingRecovery = true;
          renewSession()
            .then(() => instance.startLoad())
            .catch(() => onLog("This video couldn't be played. Try reloading the page."));
          return;
        }
        if (data.fatal) {
          // hls.js error types and details are diagnostics; keep them in the
          // console for debugging and tell the viewer something actionable.
          console.error('hls.js fatal error', data.type, data.details);
          onLog("This video couldn't be played. Try reloading the page.");
        }
      });
    }
    hls.loadSource(url);
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari's native HLS: no headers on this request either (Rule 17), the
    // cookies set moments ago are what authorize it.
    video.src = url;
    if (renewSession && !nativeHlsRecoveryByElement.has(video)) {
      nativeHlsRecoveryByElement.add(video);
      // The native player exposes no HTTP status for a failed segment, only a
      // MediaError code, so an auth failure cannot be distinguished from a
      // genuine network error as precisely as hls.js's `response.code` allows
      // -- MEDIA_ERR_NETWORK is the closest proxy and still excludes decode
      // and unsupported-source errors, which a session renewal cannot fix.
      let awaitingRecovery = false;
      video.addEventListener('loadeddata', () => {
        awaitingRecovery = false;
      });
      video.addEventListener('error', () => {
        const isNetworkError = video.error?.code === MediaError.MEDIA_ERR_NETWORK;
        if (isNetworkError && !awaitingRecovery) {
          awaitingRecovery = true;
          renewSession()
            .then(() => {
              video.src = url;
            })
            .catch(() => onLog("This video couldn't be played. Try reloading the page."));
          return;
        }
        onLog("This video couldn't be played. Try reloading the page.");
      });
    }
  } else {
    onLog('This browser cannot play this video. Try a recent Chrome, Safari, Firefox or Edge.');
  }
}

/** Tears down any hls.js instance attached to this element, e.g. before it unmounts. */
export function detachHls(video: HTMLVideoElement | null) {
  if (!video) return;
  activeHlsByElement.get(video)?.destroy();
  activeHlsByElement.delete(video);
}
