import { afterEach, describe, expect, it, vi } from 'vitest';
import { avatarHue, formatCount, formatHandle, relativeTime } from '../ui';

describe('formatCount', () => {
  it('keeps counts under a thousand exact', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(999)).toBe('999');
  });

  // The label has room for four characters, not four digits.
  it('abbreviates thousands and millions, dropping a trailing .0', () => {
    expect(formatCount(1000)).toBe('1K');
    expect(formatCount(1200)).toBe('1.2K');
    expect(formatCount(12_000)).toBe('12K');
    expect(formatCount(1_000_000)).toBe('1M');
    expect(formatCount(1_250_000)).toBe('1.3M');
  });
});

describe('relativeTime', () => {
  afterEach(() => vi.useRealTimers());

  it('describes ages rather than wall-clock times', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-08T12:00:00Z'));
    expect(relativeTime('2026-09-08T11:59:30Z')).toBe('just now');
    expect(relativeTime('2026-09-08T11:30:00Z')).toBe('30m ago');
    expect(relativeTime('2026-09-08T05:00:00Z')).toBe('7h ago');
    expect(relativeTime('2026-09-05T12:00:00Z')).toBe('3d ago');
  });

  it('returns the input unchanged when it is not a date', () => {
    expect(relativeTime('not a date')).toBe('not a date');
  });

  it('never reports a negative age for a clock-skewed future timestamp', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-08T12:00:00Z'));
    expect(relativeTime('2026-09-08T12:05:00Z')).toBe('just now');
  });
});

describe('identity helpers', () => {
  it('gives one id the same hue every time, so a creator keeps one colour', () => {
    expect(avatarHue('abc')).toBe(avatarHue('abc'));
    expect(avatarHue('abc')).toBeGreaterThanOrEqual(0);
    expect(avatarHue('abc')).toBeLessThan(360);
  });

  it('renders a real handle with the sigil', () => {
    expect(formatHandle('dat')).toBe('@dat');
  });

  // The fallback exists for the moment before a list refetches and supplies the
  // real handle. It reproduces what the old fabricator produced, which is also
  // what the migration seeded existing accounts with, so the two never disagree.
  it('falls back to the id-derived form, matching the backfill', () => {
    expect(formatHandle(null, '3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b')).toBe('@3f1a2b4c5d');
  });

  it('renders nothing when it has neither', () => {
    expect(formatHandle(null)).toBe('');
  });
});
