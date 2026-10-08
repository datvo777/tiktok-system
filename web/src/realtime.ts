import { createContext, useContext, useEffect, useState } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { ApiError, getMe } from './api';

/**
 * Server-sent "something changed" hints, next to polling.
 *
 * The stream carries no data the app relies on: each event only says "ask the API again". Losing
 * it, or never having it, makes the app slower to notice a change, never wrong. That is why every
 * failure path here ends in "fall back to polling" rather than in an error, and why a (re)connect
 * reloads everything it might have missed instead of trying to replay.
 */

export const STREAM_URL = '/api/v1/events/stream';

/** Inbox polling while there is no stream, and the safety net while there is one. */
export const INBOX_POLL_MS = 10_000;
export const INBOX_POLL_MS_WITH_STREAM = 60_000;

export function inboxPollMs(streamConnected: boolean): number {
  return streamConnected ? INBOX_POLL_MS_WITH_STREAM : INBOX_POLL_MS;
}

/**
 * Whether to use the stream. Off by default (the server side is too); `?realtime=on|off` in the
 * URL overrides the build setting so the same build can be run both ways for comparison.
 */
export function realtimeEnabled(
  search: string = typeof window === 'undefined' ? '' : window.location.search,
  buildFlag: string | undefined = import.meta.env.VITE_REALTIME,
): boolean {
  const override = new URLSearchParams(search).get('realtime');
  if (override === 'on') return true;
  if (override === 'off') return false;
  return buildFlag === 'true';
}

/** The queries a hint can make stale. A prefix, so `['video', id]` is covered too. */
const STALE_ON_HINT = [['notifications'], ['myVideos'], ['video']] as const;

export function invalidateForHint(queryClient: QueryClient): void {
  for (const queryKey of STALE_ON_HINT) {
    void queryClient.invalidateQueries({ queryKey });
  }
}

/** The part of EventSource this uses, so tests can supply a fake. */
export interface SourceLike {
  readonly readyState: number;
  onopen: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
  addEventListener(type: string, listener: (event: unknown) => void): void;
  close(): void;
}

const CLOSED = 2;

export interface ConnectionDeps {
  createSource: (url: string) => SourceLike;
  /** Reload everything a hint could have changed. */
  invalidate: () => void;
  /** True while the session is still good. Called when the stream is refused outright. */
  sessionIsValid: () => Promise<boolean>;
  onSessionLost: () => void;
  onStatus: (connected: boolean) => void;
  random?: () => number;
}

const DEBOUNCE_MS = 300;
const MIN_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

/**
 * One stream, kept open while signed in.
 *
 * <p>EventSource retries by itself only while the connection merely dropped (readyState
 * CONNECTING). When the server refuses it, which is what a 401 (expired or revoked session) or a
 * 503 (instance full) looks like from here, it gives up and goes CLOSED. This takes over from
 * there: confirm the session is still good (a dead one ends the stream for good), then reconnect
 * with growing, jittered delays so a restarted server is not hit by every client at once.
 */
export class RealtimeConnection {
  private source: SourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private stopped = false;

  constructor(private readonly deps: ConnectionDeps) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.source?.close();
    this.source = null;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.retryTimer = this.debounceTimer = null;
    this.deps.onStatus(false);
  }

  private open(): void {
    const source = this.deps.createSource(STREAM_URL);
    this.source = source;

    source.onopen = () => {
      this.failures = 0;
      this.deps.onStatus(true);
      // Anything that happened while there was no stream (a restart, a dropped connection) was not
      // pushed to us, so reload instead of assuming nothing did.
      this.deps.invalidate();
    };

    source.addEventListener('changed', () => {
      // A burst of events (several videos finishing together) is one reload, not one each.
      if (this.debounceTimer) return;
      this.debounceTimer = setTimeout(() => {
        this.debounceTimer = null;
        this.deps.invalidate();
      }, DEBOUNCE_MS);
    });

    source.onerror = () => {
      this.deps.onStatus(false);
      if (source.readyState !== CLOSED) {
        return; // the browser is reconnecting on its own; polling covers the gap
      }
      source.close();
      if (this.source === source) this.source = null;
      void this.afterRefusal();
    };
  }

  private async afterRefusal(): Promise<void> {
    if (this.stopped) return;
    let valid = true;
    try {
      valid = await this.deps.sessionIsValid();
    } catch {
      // Could not tell (network down, server restarting): treat it as a reason to retry, not to sign out.
    }
    if (this.stopped) return;
    if (!valid) {
      this.deps.onSessionLost();
      return;
    }
    this.failures += 1;
    const random = this.deps.random ?? Math.random;
    const ceiling = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * 2 ** (this.failures - 1));
    // "Full jitter": anywhere up to the ceiling, with a floor so the first retry is not instant.
    const delay = Math.max(MIN_BACKOFF_MS, Math.round(random() * ceiling));
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (!this.stopped) this.open();
    }, delay);
  }
}

const RealtimeStatusContext = createContext(false);

export const RealtimeStatusProvider = RealtimeStatusContext.Provider;

/** Whether the stream is up right now; panels use it to relax their own polling. */
export function useRealtimeConnected(): boolean {
  return useContext(RealtimeStatusContext);
}

/**
 * Keeps the stream open while signed in. Returns whether it is connected.
 *
 * @param onSessionLost called when the server refused the stream and `/me` confirms the session is gone
 */
export function useRealtime(signedIn: boolean, onSessionLost: () => void): boolean {
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!signedIn || !realtimeEnabled() || typeof EventSource === 'undefined') return;
    const connection = new RealtimeConnection({
      createSource: (url) => new EventSource(url) as unknown as SourceLike,
      invalidate: () => invalidateForHint(queryClient),
      sessionIsValid: async () => {
        try {
          await getMe();
          return true;
        } catch (error) {
          if (error instanceof ApiError && error.isUnauthenticated) return false;
          throw error;
        }
      },
      onSessionLost,
      onStatus: setConnected,
    });
    connection.start();
    return () => connection.stop();
    // onSessionLost is expected to be stable; re-running on its identity would reopen the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn, queryClient]);

  return connected;
}
