// Same-origin via the Vite proxy, same as web/ (brief section 12.1). `credentials`
// is deliberately left at its default.
//
// Responses are validated at this boundary rather than cast — see ./http. The
// admin surface needs the 401/403 distinction most: 401 means "not signed in",
// 403 means "signed in without the ADMIN role", and they call for different
// messages.

import { ApiError, arr, bool, jsonBody, nullableStr, num, obj, request, requestNoContent, str } from './http';

export { ApiError, ContractError } from './http';

/**
 * The bearer token in the response is deliberately not read: the HttpOnly
 * session cookie set alongside it authenticates every request this app makes.
 */
export type LoginResponse = {
  accountId: string;
  expiresAt: string;
};

export type PendingVideo = {
  videoId: string;
  creatorId: string;
  state: string;
  createdAt: string;
};

export type PendingVideoPage = {
  items: PendingVideo[];
  nextCursor: string | null;
};

export async function login(email: string, password: string): Promise<LoginResponse> {
  return request(
    '/api/v1/auth/login',
    (payload) => {
      const o = obj('login', payload);
      return { accountId: str('login', o, 'accountId'), expiresAt: str('login', o, 'expiresAt') };
    },
    jsonBody({ email, password }),
  );
}

export type MeResponse = {
  accountId: string;
  displayName: string;
  state: string;
  roles: string[];
};

/**
 * Checked once on load: the session cookie survives a page refresh even
 * though React state doesn't, so this is how the app tells "actually signed
 * out" apart from "just reloaded the page." Throws (401) if there's no valid
 * session.
 */
export async function getMe(): Promise<MeResponse> {
  return request('/api/v1/auth/me', (payload) => {
    const o = obj('me', payload);
    return {
      accountId: str('me', o, 'accountId'),
      displayName: str('me', o, 'displayName'),
      state: str('me', o, 'state'),
      roles: arr('me.roles', o['roles']).map((r, i) => {
        if (typeof r !== 'string') throw new Error(`me.roles[${i}] is not a string`);
        return r;
      }),
    };
  });
}

export async function logout(): Promise<void> {
  // Reports failure: logout revokes the token server-side, so a silent failure
  // would leave the admin believing the session had ended when it had not.
  await requestNoContent('/api/v1/auth/logout', { method: 'POST' });
}

/** True when the failure was specifically "signed in, but not an admin". */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.isForbidden;
}

const PENDING_PAGE_SIZE = 10;

/**
 * A 403 here means the session cookie is valid but does not carry the ADMIN
 * role — distinct from a 401 (not logged in at all). Cursor-paged so a queue of
 * thousands is never fetched in one response.
 *
 * <p>`limit` is caller-chosen because the two readers want different things:
 * Review pulls a working set deep enough that a reviewer never waits on the
 * network between decisions, while Operate pulls one wide page purely to size
 * the backlog. The server caps it at 100 either way.
 */
export async function listPending(cursor?: string, limit = PENDING_PAGE_SIZE): Promise<PendingVideoPage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set('cursor', cursor);
  return request(`/internal/v1/videos/pending?${params}`, (payload) => {
    const o = obj('pending', payload);
    return {
      nextCursor: nullableStr('pending', o, 'nextCursor'),
      items: arr('pending.items', o['items']).map((raw, i) => {
        const v = obj(`pending.items[${i}]`, raw);
        return {
          videoId: str(`pending.items[${i}]`, v, 'videoId'),
          creatorId: str(`pending.items[${i}]`, v, 'creatorId'),
          state: str(`pending.items[${i}]`, v, 'state'),
          createdAt: str(`pending.items[${i}]`, v, 'createdAt'),
        };
      }),
    };
  });
}

export async function approve(videoId: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/approve`, { method: 'POST' });
}

/**
 * `policyCategory` is required server-side; `reason` is optional elaboration.
 * Sending the classification as its own field — rather than packed into the
 * reason string, as the console did before the enum landed — is what makes
 * rejections countable.
 */
export async function reject(videoId: string, policyCategory: string, reason: string): Promise<void> {
  await requestNoContent(
    `/internal/v1/videos/${videoId}/reject`,
    jsonBody({ policyCategory, reason: reason || null }),
  );
}

export type PendingAppeal = {
  videoId: string;
  state: string;
  reason: string | null;
  decisionReason: string | null;
};

/** Milestone 6 (brief section 18). {@code appealId} is the video's id. */
export async function listPendingAppeals(): Promise<PendingAppeal[]> {
  return request('/internal/v1/appeals/pending', (payload) =>
    arr('appeals', payload).map((raw, i) => {
      const a = obj(`appeals[${i}]`, raw);
      return {
        videoId: str(`appeals[${i}]`, a, 'videoId'),
        state: str(`appeals[${i}]`, a, 'state'),
        reason: nullableStr(`appeals[${i}]`, a, 'reason'),
        decisionReason: nullableStr(`appeals[${i}]`, a, 'decisionReason'),
      };
    }),
  );
}

export async function approveAppeal(appealId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/appeals/${appealId}/approve`, jsonBody({ reason }));
}

export async function denyAppeal(appealId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/appeals/${appealId}/deny`, jsonBody({ reason }));
}

export async function quarantine(videoId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/quarantine`, jsonBody({ reason }));
}

export async function restore(videoId: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/restore`, { method: 'POST' });
}

export async function removeVideo(videoId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/remove`, jsonBody({ reason }));
}

export async function reprocessVideo(videoId: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/reprocess`, { method: 'POST' });
}

/** Composed from the eligibility projection plus the creator's display name (brief section 17). */
export type VideoDetail = {
  videoId: string;
  creatorId: string;
  creatorDisplayName: string;
  title: string | null;
  description: string | null;
  processingState: string;
  moderationState: string;
  publicationState: string;
  assetLifecycleState: string;
  legalServingState: string;
  isVideoEligible: boolean;
  updatedAt: string;
};

/** A 404 here means no eligibility row exists for that id yet (or ever) -- not necessarily a typo'd id. */
export async function getVideoDetail(videoId: string): Promise<VideoDetail> {
  return request(`/internal/v1/videos/${videoId}`, (payload) => {
    const o = obj('videoDetail', payload);
    return {
      videoId: str('videoDetail', o, 'videoId'),
      creatorId: str('videoDetail', o, 'creatorId'),
      creatorDisplayName: str('videoDetail', o, 'creatorDisplayName'),
      title: nullableStr('videoDetail', o, 'title'),
      description: nullableStr('videoDetail', o, 'description'),
      processingState: str('videoDetail', o, 'processingState'),
      moderationState: str('videoDetail', o, 'moderationState'),
      publicationState: str('videoDetail', o, 'publicationState'),
      assetLifecycleState: str('videoDetail', o, 'assetLifecycleState'),
      legalServingState: str('videoDetail', o, 'legalServingState'),
      isVideoEligible: bool('videoDetail', o, 'isVideoEligible'),
      updatedAt: str('videoDetail', o, 'updatedAt'),
    };
  });
}

/** Public search (creator name, title, or description) reused here for admin lookup by keyword. */
export type VideoSearchHit = {
  videoId: string;
  creatorId: string;
  creatorDisplayName: string;
  title: string | null;
  publishedAt: string;
};

export async function searchVideos(query: string): Promise<VideoSearchHit[]> {
  return request(`/api/v1/search?q=${encodeURIComponent(query)}`, (payload) => {
    const o = obj('search', payload);
    return arr('search.results', o['results']).map((raw, i) => {
      const hit = obj(`search.results[${i}]`, raw);
      return {
        videoId: str(`search.results[${i}]`, hit, 'videoId'),
        creatorId: str(`search.results[${i}]`, hit, 'creatorId'),
        creatorDisplayName: str(`search.results[${i}]`, hit, 'creatorDisplayName'),
        title: nullableStr(`search.results[${i}]`, hit, 'title'),
        publishedAt: str(`search.results[${i}]`, hit, 'publishedAt'),
      };
    });
  });
}

export type AdminAccount = {
  accountId: string;
  email: string;
  displayName: string;
  state: string;
  roles: string[];
  createdAt: string;
};

/** Substring match on email, newest accounts first, capped server-side at 50. */
export async function searchAccounts(query: string): Promise<AdminAccount[]> {
  return request(`/internal/v1/accounts?q=${encodeURIComponent(query)}`, (payload) => {
    const o = obj('accounts', payload);
    return arr('accounts.items', o['items']).map((raw, i) => {
      const a = obj(`accounts.items[${i}]`, raw);
      return {
        accountId: str(`accounts.items[${i}]`, a, 'accountId'),
        email: str(`accounts.items[${i}]`, a, 'email'),
        displayName: str(`accounts.items[${i}]`, a, 'displayName'),
        state: str(`accounts.items[${i}]`, a, 'state'),
        roles: arr(`accounts.items[${i}].roles`, a['roles']).map((r, j) => {
          if (typeof r !== 'string') throw new Error(`accounts.items[${i}].roles[${j}] is not a string`);
          return r;
        }),
        createdAt: str(`accounts.items[${i}]`, a, 'createdAt'),
      };
    });
  });
}

export async function suspendAccount(accountId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/accounts/${accountId}/suspend`, jsonBody({ reason }));
}

export async function reinstateAccount(accountId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/accounts/${accountId}/reinstate`, jsonBody({ reason }));
}

export type PlaybackSessionResponse = {
  videoId: string;
  processingVersion: number;
  mode: string;
  expiresAt: string;
};

/** Admin-only; lets a moderator watch a video before approving/rejecting it. */
export async function createModeratorPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/internal/v1/videos/${videoId}/moderator-playback-session`,
    (payload) => {
      const o = obj('moderatorSession', payload);
      return {
        videoId: str('moderatorSession', o, 'videoId'),
        processingVersion: num('moderatorSession', o, 'processingVersion'),
        mode: str('moderatorSession', o, 'mode'),
        expiresAt: str('moderatorSession', o, 'expiresAt'),
      };
    },
    { method: 'POST' },
  );
}
