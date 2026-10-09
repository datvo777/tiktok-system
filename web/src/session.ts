import { ApiError } from './api';

/** The server's `code` for a 401 that means "this session has run its full length", not "bad credentials". */
export const SESSION_LIFETIME_EXCEEDED = 'SESSION_LIFETIME_EXCEEDED';

export type RefreshOutcome =
  /** A new token was issued. */
  | 'refreshed'
  /** Another tab holds the refresh; the cookie it sets is shared, so nothing is lost by waiting a round. */
  | 'skipped'
  /** The refresh failed for a reason that does not end the session (network, server error, lost a race). */
  | 'kept'
  /** The session is over: revoked, suspended, or signed out elsewhere. */
  | 'ended'
  /** The session reached its absolute lifetime; the person has to sign in again. */
  | 'ended-lifetime';

export interface RefreshDeps {
  refresh: () => Promise<unknown>;
  me: () => Promise<unknown>;
  /** Runs `fn` only if no other tab is refreshing; resolves undefined when one is. */
  withLock: <T>(fn: () => Promise<T>) => Promise<T | undefined>;
}

/**
 * One scheduled attempt to extend the session.
 *
 * <p>Refreshing revokes the token it replaces, so two tabs refreshing in the same moment send the
 * same token and the second is refused with a 401 although the session is perfectly healthy: the
 * first tab's reply has already put a new cookie in the shared jar. Treating that 401 as "signed out"
 * logged the losing tab out. So a refusal is believed only after `/me`, which uses the current
 * cookie, agrees; and a failure that is not a 401 at all (the network, a deploy) never signs anyone
 * out, it just waits for the next attempt.
 */
export async function refreshSession(deps: RefreshDeps): Promise<RefreshOutcome> {
  const outcome = await deps.withLock<RefreshOutcome>(async () => {
    try {
      await deps.refresh();
      return 'refreshed';
    } catch (error) {
      if (!(error instanceof ApiError) || !error.isUnauthenticated) return 'kept';
      if (error.code === SESSION_LIFETIME_EXCEEDED) return 'ended-lifetime';
      try {
        await deps.me();
        return 'kept';
      } catch (meError) {
        if (meError instanceof ApiError && meError.isUnauthenticated) {
          return meError.code === SESSION_LIFETIME_EXCEEDED ? 'ended-lifetime' : 'ended';
        }
        return 'kept';
      }
    }
  });
  return outcome ?? 'skipped';
}

/** Web Locks where the browser has them (every current one); otherwise just run. */
export async function withTabLock<T>(fn: () => Promise<T>): Promise<T | undefined> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  if (!locks) return fn();
  return await locks.request('sv-session-refresh', { ifAvailable: true }, async (lock) =>
    lock ? await fn() : undefined,
  );
}
