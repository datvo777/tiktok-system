// Same-origin via the Vite proxy, same as web/ (brief section 12.1). `credentials`
// is deliberately left at its default.
//
// Responses are validated at this boundary rather than cast — see ./http. The
// admin surface needs the 401/403 distinction most: 401 means "not signed in",
// 403 means "signed in without the ADMIN role", and they call for different
// messages.

import { ApiError, arr, jsonBody, nullableStr, num, obj, request, requestNoContent, str } from './http';

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
 * role — distinct from a 401 (not logged in at all). Cursor-paged (see
 * `PendingVideos` in App.tsx) so a queue of thousands is never fetched in one
 * response.
 */
export async function listPending(cursor?: string): Promise<PendingVideoPage> {
  const params = new URLSearchParams({ limit: String(PENDING_PAGE_SIZE) });
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

export async function reject(videoId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/videos/${videoId}/reject`, jsonBody({ reason }));
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
