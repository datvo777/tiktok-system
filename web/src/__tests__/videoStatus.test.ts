import { describe, expect, it } from 'vitest';
import type { VideoSummary } from '../api';
import { hasVideoInFlight, pollDelayMs, statusBadge } from '../videoStatus';

const NOW = Date.parse('2026-10-08T12:00:00Z');

function video(over: Partial<VideoSummary>): VideoSummary {
  return {
    videoId: 'v1',
    title: 't',
    processingState: 'READY',
    assetLifecycleState: 'ACTIVE',
    publicationState: null,
    createdAt: '2026-10-08T11:50:00Z',
    ...over,
  };
}

describe('hasVideoInFlight', () => {
  it('keeps polling while a recent video is still being processed', () => {
    expect(hasVideoInFlight([video({ processingState: 'TRANSCODING' })], NOW)).toBe(true);
    expect(hasVideoInFlight([video({ processingState: 'CREATED' })], NOW)).toBe(true);
  });

  it('keeps polling while publication waits on moderation', () => {
    expect(hasVideoInFlight([video({ publicationState: 'PUBLISH_PENDING' })], NOW)).toBe(true);
  });

  it('stops once everything has settled', () => {
    const settled = [
      video({ processingState: 'READY', publicationState: 'PUBLISHED' }),
      video({ processingState: 'FAILED' }),
      video({ processingState: 'EXPIRED' }),
    ];
    expect(hasVideoInFlight(settled, NOW)).toBe(false);
  });

  it('does not poll forever for an abandoned draft or a removed video', () => {
    expect(
      hasVideoInFlight([video({ processingState: 'CREATED', createdAt: '2026-10-07T12:00:00Z' })], NOW),
    ).toBe(false);
    expect(
      hasVideoInFlight([video({ processingState: 'TRANSCODING', assetLifecycleState: 'DELETED' })], NOW),
    ).toBe(false);
  });
});

describe('statusBadge', () => {
  it('does not call a draft with no processing yet "Uploading"', () => {
    expect(statusBadge(video({ processingState: 'CREATED' })).label).toBe('Waiting to process');
  });

  it('lets a lifecycle state override the processing state', () => {
    expect(statusBadge(video({ assetLifecycleState: 'REJECTED_RETAINED' })).label).toBe('Rejected');
  });
});

describe('pollDelayMs', () => {
  it('follows the server hint at first, and falls back to 2s without one', () => {
    expect(pollDelayMs(2000, 5_000)).toBe(2000);
    expect(pollDelayMs(null, 5_000)).toBe(2000);
    expect(pollDelayMs(undefined, 5_000)).toBe(2000);
  });

  it('slows down the longer the wait lasts, up to a ceiling', () => {
    expect(pollDelayMs(2000, 90_000)).toBe(4000);
    expect(pollDelayMs(2000, 5 * 60_000)).toBe(10_000);
    expect(pollDelayMs(8000, 5 * 60_000)).toBe(10_000);
  });
});
