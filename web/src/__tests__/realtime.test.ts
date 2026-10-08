import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  INBOX_POLL_MS,
  INBOX_POLL_MS_WITH_STREAM,
  RealtimeConnection,
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

  beforeEach(() => {
    vi.useFakeTimers();
    sources = [];
    invalidate = vi.fn();
    sessionIsValid = vi.fn().mockResolvedValue(true);
    onSessionLost = vi.fn();
    status = [];
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
      random: () => 1,
    });
  });
  afterEach(() => {
    connection.stop();
    vi.useRealTimers();
  });

  it('reloads on every open, since it cannot know what it missed while disconnected', () => {
    connection.start();
    sources[0]!.open();

    expect(status.at(-1)).toBe(true);
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('turns a burst of hints into one reload', () => {
    connection.start();
    sources[0]!.open();
    invalidate.mockClear();

    sources[0]!.hint();
    sources[0]!.hint();
    sources[0]!.hint();
    expect(invalidate).not.toHaveBeenCalled();
    vi.advanceTimersByTime(300);

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
    await vi.advanceTimersByTimeAsync(60_000);

    expect(onSessionLost).toHaveBeenCalledTimes(1);
    expect(sources).toHaveLength(1);
  });

  it('reconnects after a refusal with a growing delay while the session is still good', async () => {
    connection.start();

    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(999);
    expect(sources).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(2);

    sources[1]!.refuse();
    await vi.advanceTimersByTimeAsync(1_999);
    expect(sources).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sources).toHaveLength(3);
    expect(onSessionLost).not.toHaveBeenCalled();
  });

  it('starts the delay over once a connection succeeds', async () => {
    connection.start();
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(1_000);
    sources[1]!.refuse();
    await vi.advanceTimersByTimeAsync(2_000);
    sources[2]!.open();

    sources[2]!.refuse();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(sources).toHaveLength(4);
  });

  it('retries, rather than signing out, when it cannot even ask whether the session is good', async () => {
    sessionIsValid.mockRejectedValueOnce(new Error('network down'));
    connection.start();

    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(onSessionLost).not.toHaveBeenCalled();
    expect(sources).toHaveLength(2);
  });

  it('closes the stream and any pending retry when stopped', async () => {
    connection.start();
    sources[0]!.refuse();
    await vi.advanceTimersByTimeAsync(0);

    connection.stop();
    await vi.advanceTimersByTimeAsync(60_000);

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
