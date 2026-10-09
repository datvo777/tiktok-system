import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  INBOX_POLL_MS,
  INBOX_POLL_MS_WITH_STREAM,
  RealtimeConnection,
  TIMING,
  inboxPollMs,
  realtimeEnabled,
  type SourceLike,
} from '../realtime';

class FakeSource implements SourceLike {
  readyState = 0;
  onopen: ((event: unknown) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  closed = false;
  private listeners = new Map<string, (event: unknown) => void>();
  addEventListener(type: string, listener: (event: unknown) => void) {
    this.listeners.set(type, listener);
  }
  close() {
    this.closed = true;
    this.readyState = 2;
  }
  // test controls
  open() {
    this.readyState = 1;
    this.onopen?.({});
  }
  hint() {
    this.listeners.get('changed')?.({});
  }
  /** The browser gave up (a 401 or 503 on connect). */
  refuse() {
    this.readyState = 2;
    this.onerror?.({});
  }
  /** The server closed the stream on purpose and said whether to come back. */
  bye(payload: { reason: string; reconnect: boolean; after: number }) {
    this.listeners.get('bye')?.({ data: JSON.stringify(payload) });
  }
  /** The connection dropped and the browser is retrying by itself. */
  drop() {
    this.readyState = 0;
    this.onerror?.({});
  }
}

describe('RealtimeConnection', () => {
  let sources: FakeSource[];
  let invalidate: ReturnType<typeof vi.fn>;
  let sessionIsValid: ReturnType<typeof vi.fn>;
  let onSessionLost: ReturnType<typeof vi.fn>;
  let status: boolean[];
  let connection: RealtimeConnection;
  let random: number;

  beforeEach(() => {
    vi.useFakeTimers();
    sources = [];
    invalidate = vi.fn();
    sessionIsValid = vi.fn().mockResolvedValue(true);
    onSessionLost = vi.fn();
    status = [];
    random = 1;
    connection = new RealtimeConnection({
      createSource: () => {
        const s = new FakeSource();
        sources.push(s);
        return s;
      },
      invalidate,
      sessionIsValid,
      onSessionLost,
      onStatus: (c) => status.push(c),
      random: () => random,
    });
  });
  afterEach(() => {
    connection.stop();
    vi.useRealTimers();
  });

  it('reloads after a connect, but not on the same tick as everyone else', () => {
    connection.start();
    sources[0]!.open();

    expect(status.at(-1)).toBe(true);
    expect(invalidate).not.toHaveBeenCalled();
    vi.advanceTimersByTime(TIMING.refetchJitterMs);
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('turns a burst of hints into one reload', () => {
    connection.start();
    sources[0]!.open();
    vi.advanceTimersByTime(TIMING.refetchJitterMs);
    invalidate.mockClear();

    sources[0]!.hint();
    sources[0]!.hint();
    sources[0]!.hint();
    expect(invalidate).not.toHaveBeenCalled();
    vi.advanceTimersByTime(TIMING.debounceMs);

    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('leaves a dropped connection to the browser and just reports it down', () => {
    connection.start();
    sources[0]!.open();

    sources[0]!.drop();

    expect(status.at(-1)).toBe(false);
    expect(sources).toHaveLength(1);
    expect(sessionIsValid).not.toHaveBeenCalled();
  });

  it('stops for good when the refusal was because the session is gone', async () => {
    sessionIsValid.mockResolvedValue(false);
    connection.start();

    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(10 * 60_000);

    expect(onSessionLost).toHaveBeenCalledTimes(1);
    expect(sources).toHaveLength(1);
  });

  it('draws the first reconnect from a wide window, so a crowd is spread rather than marched back', async () => {
    connection.start();
    random = 1; // the far end of the window
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs - 1);
    expect(sources).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(2);

    // And a client that drew the near end comes back soon after the floor, not after the window.
    random = 0;
    sources[1]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.minDelayMs);
    expect(sources).toHaveLength(3);
  });

  it('doubles the window after each failure up to a ceiling', async () => {
    connection.start();
    random = 1;
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs);
    sources[1]!.refuse();
    await vi.advanceTimersByTimeAsync(2 * TIMING.firstWindowMs - 1);
    expect(sources).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(3);
  });

  it('does not forgive failures just because a connection opened', async () => {
    connection.start();
    random = 1;
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs);
    sources[1]!.open();
    sources[1]!.refuse(); // accepted, then dropped at once: a flapping server

    await vi.advanceTimersByTimeAsync(2 * TIMING.firstWindowMs - 1);
    expect(sources).toHaveLength(2); // still waiting out the doubled window, not back to the first one
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(3);
  });

  it('starts the window over once a connection has held', async () => {
    connection.start();
    random = 1;
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs);
    sources[1]!.open();
    await vi.advanceTimersByTimeAsync(TIMING.stableAfterMs);

    sources[1]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs);

    expect(sources).toHaveLength(3);
  });

  it('retries, rather than signing out, when it cannot even ask whether the session is good', async () => {
    sessionIsValid.mockRejectedValueOnce(new Error('network down'));
    connection.start();

    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(TIMING.firstWindowMs);

    expect(onSessionLost).not.toHaveBeenCalled();
    expect(sources).toHaveLength(2);
  });

  it('stops trying for a while after repeated failures, then tries once more', async () => {
    connection.start();
    for (let i = 0; i < TIMING.breakerFailures - 1; i++) {
      sources.at(-1)!.drop();
    }
    expect(sources.at(-1)!.closed).toBe(false);

    sources.at(-1)!.drop(); // the fifth within two minutes

    expect(sources.at(-1)!.closed).toBe(true);
    const count = sources.length;
    await vi.advanceTimersByTimeAsync(TIMING.breakerPauseMinMs - 1);
    expect(sources).toHaveLength(count);
    await vi.advanceTimersByTimeAsync(TIMING.breakerPauseMaxMs);
    expect(sources).toHaveLength(count + 1);
  });

  it('does not come back when the server says it pushed this stream out', async () => {
    connection.start();
    sources[0]!.open();

    sources[0]!.bye({ reason: 'evicted', reconnect: false, after: 0 });
    await vi.advanceTimersByTimeAsync(60 * 60_000);

    expect(sources[0]!.closed).toBe(true);
    expect(sources).toHaveLength(1);
    expect(status.at(-1)).toBe(false);
  });

  it('comes back at the moment the server chose, plus a little jitter', async () => {
    connection.start();
    sources[0]!.open();
    random = 0;

    sources[0]!.bye({ reason: 'shutdown', reconnect: true, after: 12_000 });
    await vi.advanceTimersByTimeAsync(11_999);
    expect(sources).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(sources).toHaveLength(2);
  });

  it('closes the stream of a tab that stays hidden, and reconnects when it is shown again', async () => {
    connection.start();
    sources[0]!.open();

    connection.hidden();
    await vi.advanceTimersByTimeAsync(TIMING.hiddenAfterMs);
    expect(sources[0]!.closed).toBe(true);
    expect(status.at(-1)).toBe(false);

    random = 0;
    connection.visible();
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(2);
  });

  it('keeps the stream of a tab that was hidden only briefly', async () => {
    connection.start();
    sources[0]!.open();

    connection.hidden();
    await vi.advanceTimersByTimeAsync(TIMING.hiddenAfterMs - 1);
    connection.visible();
    await vi.advanceTimersByTimeAsync(TIMING.hiddenAfterMs * 2);

    expect(sources[0]!.closed).toBe(false);
    expect(sources).toHaveLength(1);
  });

  it('closes the stream and any pending retry when stopped', async () => {
    connection.start();
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(0);

    connection.stop();
    await vi.advanceTimersByTimeAsync(60 * 60_000);

    expect(sources).toHaveLength(1);
    expect(status.at(-1)).toBe(false);
  });
});

describe('inboxPollMs', () => {
  it('slows the inbox poll down only while the stream is up', () => {
    expect(inboxPollMs(false)).toBe(INBOX_POLL_MS);
    expect(inboxPollMs(true)).toBe(INBOX_POLL_MS_WITH_STREAM);
    expect(INBOX_POLL_MS_WITH_STREAM).toBeGreaterThan(INBOX_POLL_MS);
  });
});

describe('realtimeEnabled', () => {
  it('is off unless asked for', () => {
    expect(realtimeEnabled('', undefined)).toBe(false);
    expect(realtimeEnabled('', 'false')).toBe(false);
  });

  it('follows the build flag', () => {
    expect(realtimeEnabled('', 'true')).toBe(true);
  });

  it('lets the URL override the build either way, so one build can run both modes', () => {
    expect(realtimeEnabled('?realtime=on', undefined)).toBe(true);
    expect(realtimeEnabled('?realtime=off', 'true')).toBe(false);
  });
});
