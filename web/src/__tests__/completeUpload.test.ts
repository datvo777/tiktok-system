import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, completeUploadWithRetry, UploadCancelled } from '../api';

const uploadBody = {
  uploadId: 'u1',
  videoId: 'v1',
  status: 'COMPLETED',
  sizeBytes: 10,
  expiresAt: '2026-01-01T00:00:00Z',
};

function problem(status: number, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ detail: 'nope' }), { status, headers });
}

describe('completeUploadWithRetry', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('retries a 503, waiting for Retry-After, and returns the eventual success', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(problem(503, { 'Retry-After': '5' }))
      .mockResolvedValueOnce(new Response(JSON.stringify(uploadBody), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = completeUploadWithRetry('u1');
    await vi.advanceTimersByTimeAsync(4_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await result;
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not retry a failure that retrying cannot fix', async () => {
    const fetchMock = vi.fn().mockResolvedValue(problem(410));
    vi.stubGlobal('fetch', fetchMock);

    await expect(completeUploadWithRetry('u1')).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('gives up after a bounded number of attempts and rethrows the last error', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(problem(503)));
    vi.stubGlobal('fetch', fetchMock);

    const result = completeUploadWithRetry('u1');
    const assertion = expect(result).rejects.toMatchObject({ status: 503 });
    await vi.runAllTimersAsync();
    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('stops waiting when cancelled', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(problem(503))));
    const controller = new AbortController();

    const result = completeUploadWithRetry('u1', controller.signal);
    const assertion = expect(result).rejects.toBeInstanceOf(UploadCancelled);
    await vi.advanceTimersByTimeAsync(10);
    controller.abort();
    await assertion;
  });
});
