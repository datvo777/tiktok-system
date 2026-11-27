import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { SESSION_LIFETIME_EXCEEDED, refreshSession, type RefreshDeps } from '../session';

const unauthenticated = (code?: string) => new ApiError(401, 'Unauthorized', undefined, code);
const run = <T>(fn: () => Promise<T>) => fn();

function deps(over: Partial<RefreshDeps> = {}): RefreshDeps {
  return {
    refresh: vi.fn().mockResolvedValue({}),
    me: vi.fn().mockResolvedValue({}),
    withLock: run,
    ...over,
  };
}

describe('refreshSession', () => {
  it('reports a successful refresh', async () => {
    expect(await refreshSession(deps())).toBe('refreshed');
  });

  it('does nothing when another tab already holds the refresh', async () => {
    const d = deps({ withLock: async () => undefined });

    expect(await refreshSession(d)).toBe('skipped');
    expect(d.refresh).not.toHaveBeenCalled();
  });

  it('does not sign out on a 401 that /me contradicts: the other tab won the race', async () => {
    const d = deps({ refresh: vi.fn().mockRejectedValue(unauthenticated()) });

    expect(await refreshSession(d)).toBe('kept');
    expect(d.me).toHaveBeenCalledTimes(1);
  });

  it('signs out when /me agrees the session is gone', async () => {
    const d = deps({
      refresh: vi.fn().mockRejectedValue(unauthenticated()),
      me: vi.fn().mockRejectedValue(unauthenticated()),
    });

    expect(await refreshSession(d)).toBe('ended');
  });

  it('says so when the session ran its full length, without asking /me', async () => {
    const d = deps({ refresh: vi.fn().mockRejectedValue(unauthenticated(SESSION_LIFETIME_EXCEEDED)) });

    expect(await refreshSession(d)).toBe('ended-lifetime');
    expect(d.me).not.toHaveBeenCalled();
  });

  it('keeps the session on failures that are not a 401', async () => {
    expect(
      await refreshSession(deps({ refresh: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')) })),
    ).toBe('kept');
    expect(
      await refreshSession(deps({ refresh: vi.fn().mockRejectedValue(new ApiError(503, 'down')) })),
    ).toBe('kept');
  });

  it('keeps the session when it cannot even ask /me', async () => {
    const d = deps({
      refresh: vi.fn().mockRejectedValue(unauthenticated()),
      me: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    });

    expect(await refreshSession(d)).toBe('kept');
  });
});
