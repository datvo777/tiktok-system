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

/** Waits and thresholds, in one place; each is explained where it is used. */
export const TIMING = {
  debounceMs: 300,
  /** The first reconnect after a refusal is spread over this much, not over one second (see afterFailure). */
  firstWindowMs: 10_000,
  maxWindowMs: 60_000,
  minDelayMs: 1_000,
  /** A connection must hold this long before its failures are forgiven. */
  stableAfterMs: 30_000,
  /** The refetch after (re)connecting is put off by up to this much. */
  refetchJitterMs: 5_000,
  /** This many failures within the window trips the breaker. */
  breakerFailures: 5,
  breakerWindowMs: 120_000,
  breakerPauseMinMs: 5 * 60_000,
  breakerPauseMaxMs: 10 * 60_000,
  /** A tab hidden this long closes its stream; showing it again reconnects within a few seconds. */
  hiddenAfterMs: 60_000,
  visibleJitterMs: 3_000,
  /**
   * A stream that has said nothing for this long is treated as dead. The server pings every 20 s
   * (shortvideo.realtime.heartbeat-interval), so this tolerates two missed pings; keep it above
   * twice that interval if the server's is raised.
   */
  silenceMs: 45_000,
} as const;

const CLOSED = 2;

/**
 * One stream, kept open while signed in.
 *
 * <p>After a restart every client comes back at once, and each connection costs the server database
 * work. The browser retries by itself while the connection merely dropped (readyState CONNECTING),
 * using the delay each stream carried in its own random `retry:` field. When the server refuses it
 * (a 401, or a 503 from the admission gate) EventSource gives up and goes CLOSED, and this takes
 * over, spreading the crowd rather than marching it back together:
 *
 * <ul>
 *   <li>the first wait after a refusal is drawn from a wide window (10 s), then the window doubles
 *       up to a minute, with full jitter, so thousands of clients land on thousands of moments;
 *   <li>the count of failures is only forgiven once a connection has held for 30 s, otherwise a
 *       server that accepts and then drops would keep every client at the shortest wait;
 *   <li>five failures in two minutes stop the attempts for five to ten minutes (the polling that
 *       runs without a stream carries on), which also covers an environment that cannot hold a
 *       stream open at all;
 *   <li>the reload after connecting is itself put off by a few random seconds;
 *   <li>a stream the server closes on purpose says whether to come back (a `bye` event): not when
 *       another tab pushed this one out, since reconnecting would push that one out in turn.
 * </ul>
 */
export class RealtimeConnection {
  private source: SourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private refetchTimer: ReturnType<typeof setTimeout> | null = null;
  private stableTimer: ReturnType<typeof setTimeout> | null = null;
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null;
  private silenceTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private recentFailures: number[] = [];
  private stopped = false;
  /** The server said not to reconnect; polling carries on without a stream. */
  private retired = false;
  private suspendedByVisibility = false;

  constructor(private readonly deps: ConnectionDeps) {}

  private random(): number {
    return (this.deps.random ?? Math.random)();
  }

  start(): void {
    this.stopped = false;
    this.retired = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.closeSource();
    for (const timer of [
      this.retryTimer,
      this.debounceTimer,
      this.refetchTimer,
      this.stableTimer,
      this.hiddenTimer,
      this.silenceTimer,
    ]) {
      if (timer) clearTimeout(timer);
    }
    this.retryTimer =
      this.debounceTimer =
      this.refetchTimer =
      this.stableTimer =
      this.hiddenTimer =
      this.silenceTimer =
        null;
    this.deps.onStatus(false);
  }

  /** The tab was hidden. A tab nobody is looking at does not need a stream, and each one is a connection. */
  hidden(): void {
    if (this.stopped || this.hiddenTimer) return;
    this.hiddenTimer = setTimeout(() => {
      this.hiddenTimer = null;
      this.suspendedByVisibility = true;
      this.clearRetry();
      this.closeSource();
      this.deps.onStatus(false);
    }, TIMING.hiddenAfterMs);
  }

  /** The tab is visible again: cancel a pending close, or reconnect a stream that was closed for hiding. */
  visible(): void {
    if (this.hiddenTimer) {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
    }
    if (this.stopped || !this.suspendedByVisibility || this.retired) return;
    this.suspendedByVisibility = false;
    // Everyone who left a laptop closed over lunch comes back around the same time.
    this.scheduleOpen(this.random() * TIMING.visibleJitterMs);
  }

  private closeSource(): void {
    this.source?.close();
    this.source = null;
    this.clearSilence();
  }

  private clearSilence(): void {
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.silenceTimer = null;
  }

  /** Called on open and on every event: the stream is alive, so give it another silence allowance. */
  private heard(source: SourceLike): void {
    this.clearSilence();
    this.silenceTimer = setTimeout(() => {
      this.silenceTimer = null;
      if (this.source !== source || this.stopped) return;
      // No FIN ever arrived, so EventSource still believes it is connected and would never retry.
      this.deps.onStatus(false);
      this.closeSource();
      if (this.noteFailure()) {
        this.scheduleOpen(
          TIMING.breakerPauseMinMs + this.random() * (TIMING.breakerPauseMaxMs - TIMING.breakerPauseMinMs),
        );
        return;
      }
      void this.afterRefusal();
    }, TIMING.silenceMs);
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private scheduleOpen(delayMs: number): void {
    this.clearRetry();
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (!this.stopped && !this.retired) this.open();
    }, delayMs);
  }

  private open(): void {
    const source = this.deps.createSource(STREAM_URL);
    this.source = source;

    source.onopen = () => {
      this.deps.onStatus(true);
      this.heard(source);
      // Not "failures = 0" yet: only a connection that lasts has earned that.
      if (this.stableTimer) clearTimeout(this.stableTimer);
      this.stableTimer = setTimeout(() => {
        this.stableTimer = null;
        this.failures = 0;
        this.recentFailures = [];
      }, TIMING.stableAfterMs);
      // Anything that happened while there was no stream was not pushed to us, so reload instead of
      // assuming nothing did, but not on the same tick as every other client that just reconnected.
      if (this.refetchTimer) clearTimeout(this.refetchTimer);
      this.refetchTimer = setTimeout(() => {
        this.refetchTimer = null;
        this.deps.invalidate();
      }, this.random() * TIMING.refetchJitterMs);
    };

    source.addEventListener('ping', () => this.heard(source));

    source.addEventListener('changed', () => {
      this.heard(source);
      // A burst of events (several videos finishing together) is one reload, not one each.
      if (this.debounceTimer) return;
      this.debounceTimer = setTimeout(() => {
        this.debounceTimer = null;
        this.deps.invalidate();
      }, TIMING.debounceMs);
    });

    source.addEventListener('bye', (event) => this.onBye(source, event));

    source.onerror = () => {
      this.deps.onStatus(false);
      this.clearSilence();
      if (this.stableTimer) {
        clearTimeout(this.stableTimer);
        this.stableTimer = null;
      }
      if (this.noteFailure()) {
        // Too many in a row: stop trying for a while instead of keeping a struggling server busy.
        this.closeSource();
        this.scheduleOpen(
          TIMING.breakerPauseMinMs + this.random() * (TIMING.breakerPauseMaxMs - TIMING.breakerPauseMinMs),
        );
        return;
      }
      if (source.readyState !== CLOSED) {
        return; // the browser is reconnecting on its own, after the delay the server gave it
      }
      source.close();
      if (this.source === source) this.source = null;
      void this.afterRefusal();
    };
  }

  /** @return true when the breaker has just tripped. */
  private noteFailure(): boolean {
    const nowMs = Date.now();
    this.recentFailures = [...this.recentFailures.filter((t) => nowMs - t < TIMING.breakerWindowMs), nowMs];
    if (this.recentFailures.length >= TIMING.breakerFailures) {
      this.recentFailures = [];
      return true;
    }
    return false;
  }

  private onBye(source: SourceLike, event: unknown): void {
    let reconnect = true;
    let afterMs = 0;
    try {
      const data = JSON.parse((event as { data?: string }).data ?? '{}') as {
        reconnect?: boolean;
        after?: number;
      };
      reconnect = data.reconnect !== false;
      afterMs = typeof data.after === 'number' ? data.after : 0;
    } catch {
      // Unreadable: treat it as an ordinary close.
    }
    source.close();
    if (this.source === source) this.source = null;
    this.deps.onStatus(false);
    if (!reconnect) {
      this.retired = true; // polling covers it from here
      return;
    }
    // The server chose a moment for this client; a little more spread on top of it.
    this.scheduleOpen(afterMs + this.random() * 2_000);
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
    const window = Math.min(TIMING.maxWindowMs, TIMING.firstWindowMs * 2 ** (this.failures - 1));
    // Full jitter across the whole window, with a floor so a retry is never immediate.
    this.scheduleOpen(Math.max(TIMING.minDelayMs, Math.round(this.random() * window)));
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
    const onVisibility = () => (document.hidden ? connection.hidden() : connection.visible());
    document.addEventListener('visibilitychange', onVisibility);
    if (document.hidden) connection.hidden();
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      connection.stop();
    };
    // onSessionLost is expected to be stable; re-running on its identity would reopen the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn, queryClient]);

  return connected;
}
