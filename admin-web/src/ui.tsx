import Hls from 'hls.js';
import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { createModeratorPreviewSession } from './api';

/* ============================================================ toasts == */

type Toast = { id: number; kind: 'success' | 'error'; message: string };
type PushToast = (kind: 'success' | 'error', message: string) => void;

const ToastContext = createContext<PushToast | null>(null);

export function useToast(): PushToast {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const pushToast: PushToast = (kind, message) => {
    const id = ++nextId.current;
    setToasts((current) => [...current, { id, kind, message }]);
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 4000);
  };

  return (
    <ToastContext.Provider value={pushToast}>
      {children}
      <div className="toasts">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind === 'error' ? 'toast-error' : 'toast-success'}`}>
            <span>{t.message}</span>
            <button aria-label="Dismiss" onClick={() => setToasts((c) => c.filter((x) => x.id !== t.id))}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/* ======================================================== state chips == */

/**
 * The five state axes each use their own enum, so rather than enumerate every
 * value this keys off the substrings they share. Getting a chip wrong is
 * cosmetic; the literal state text is always rendered beside it.
 */
function chipTone(state: string): string {
  if (/READY|APPROVED|PUBLISHED|ACTIVE|DURABLE|CLEAR/.test(state)) return 'chip-ok';
  if (/REJECTED|SUSPENDED|REMOVED|FAILED|DELETED|QUARANTINE/.test(state)) return 'chip-crit';
  if (/PENDING|TRANSCODING|UPLOAD|PRIVATE|RESTRICTED|APPEAL/.test(state)) return 'chip-warn';
  return 'chip-info';
}

export function StateChip({ state }: { state: string }) {
  return <span className={`chip ${chipTone(state)}`}>{state.replaceAll('_', ' ').toLowerCase()}</span>;
}

/* ========================================================== entity id == */

/**
 * A full UUID eats most of a line and is never read character by character —
 * it is copied. Truncating to the leading segment keeps rows scannable while
 * click-to-copy preserves the only interaction anyone actually wants.
 */
export function EntityId({ label, value }: { label?: string; value: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <button
      className="eid"
      title={`${label ? label + ' ' : ''}${value} — click to copy`}
      onClick={() => {
        void navigator.clipboard
          .writeText(value)
          .then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1200);
          })
          .catch(() => {
            /* Clipboard permission can be refused; the title attribute still shows the full id. */
          });
      }}
    >
      {label && <span className="eid-k">{label}</span>}
      <span>{value.slice(0, 8)}…</span>
      {copied && <span className="eid-copied">copied</span>}
    </button>
  );
}

/* ============================================================== timing == */

/** "4m", "2h 10m", "3d" — an ISO timestamp tells a reviewer nothing at a glance. */
export function ageSince(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 0) return '—';
  const mins = Math.floor(ms / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ${mins % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

/* ============================================================== player == */

type PlayerStatus = 'loading' | 'ready' | 'error';

/**
 * Admin playback. One hls.js instance per mount, reused across source changes
 * via loadSource() rather than destroy()+new — destroying and immediately
 * re-attaching a fresh MediaSource to the same element is a known browser race
 * (it surfaces as "mediaSourceRequiresReset"), and reusing one instance
 * sidesteps it instead of trying to win it. That matters far more here than in
 * the old list preview: in Review the source changes on every decision.
 */
export function ModeratorPlayer({
  videoId,
  videoRef,
  autoPlay = false,
}: {
  videoId: string;
  videoRef?: React.RefObject<HTMLVideoElement>;
  autoPlay?: boolean;
}) {
  const internalRef = useRef<HTMLVideoElement>(null);
  const ref = videoRef ?? internalRef;
  const hlsRef = useRef<Hls | null>(null);
  const [status, setStatus] = useState<PlayerStatus>('loading');
  const [message, setMessage] = useState<string | null>(null);
  const [orientation, setOrientation] = useState<'portrait' | 'landscape' | null>(null);

  useEffect(
    () => () => {
      hlsRef.current?.destroy();
      hlsRef.current = null;
    },
    [],
  );

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    setMessage(null);
    setOrientation(null);

    createModeratorPreviewSession(videoId)
      .then((result) => {
        if (cancelled) return;
        const el = ref.current;
        if (!el) return;
        const url = `/media/videos/${videoId}/${result.processingVersion}/master.m3u8`;

        if (Hls.isSupported()) {
          if (!hlsRef.current) {
            const hls = new Hls();
            hlsRef.current = hls;
            hls.attachMedia(el);
            hls.on(Hls.Events.ERROR, (_event, data) => {
              if (data.fatal) {
                setStatus('error');
                setMessage(`Playback failed — ${data.type}: ${data.details}`);
              }
            });
          }
          hlsRef.current.loadSource(url);
          setStatus('ready');
        } else if (el.canPlayType('application/vnd.apple.mpegurl')) {
          el.src = url;
          setStatus('ready');
        } else {
          setStatus('error');
          setMessage('This browser supports neither MSE (hls.js) nor native HLS.');
        }
      })
      .catch((error) => {
        if (cancelled) return;
        setStatus('error');
        setMessage((error as Error).message);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoId]);

  const shape = orientation === null ? 'is-unknown' : `is-${orientation}`;

  return (
    <div className="player-wrap">
      <video
        ref={ref}
        className={`player ${shape}`}
        controls
        loop
        muted
        autoPlay={autoPlay}
        playsInline
        onLoadedMetadata={(e) => {
          const { videoWidth, videoHeight } = e.currentTarget;
          if (videoWidth && videoHeight) setOrientation(videoWidth >= videoHeight ? 'landscape' : 'portrait');
        }}
      />
      {status !== 'ready' && (
        <div className={`player-status${status === 'error' ? ' is-error' : ''}`}>
          {status === 'loading' ? 'Requesting preview session…' : message}
        </div>
      )}
    </div>
  );
}
