// Same-origin via the Vite proxy, same as web/ (brief section 12.1). `credentials`
// is deliberately left at its default.

export type LoginResponse = {
  accountId: string;
  token: string;
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

async function readError(response: Response): Promise<string> {
  try {
    const problem = await response.json();
    return problem.detail ?? problem.title ?? `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}

export async function login(email: string, password: string): Promise<LoginResponse> {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
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
  const response = await fetch('/api/v1/auth/me');
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function logout(): Promise<void> {
  await fetch('/api/v1/auth/logout', { method: 'POST' });
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
  const response = await fetch(`/internal/v1/videos/pending?${params}`);
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function approve(videoId: string): Promise<void> {
  const response = await fetch(`/internal/v1/videos/${videoId}/approve`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
}

export async function reject(videoId: string, reason: string): Promise<void> {
  const response = await fetch(`/internal/v1/videos/${videoId}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
}

export type PendingAppeal = {
  videoId: string;
  state: string;
  reason: string | null;
  decisionReason: string | null;
};

/** Milestone 6 (brief section 18). {@code appealId} is the video's id. */
export async function listPendingAppeals(): Promise<PendingAppeal[]> {
  const response = await fetch('/internal/v1/appeals/pending');
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function approveAppeal(appealId: string, reason: string): Promise<void> {
  const response = await fetch(`/internal/v1/appeals/${appealId}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
}

export async function denyAppeal(appealId: string, reason: string): Promise<void> {
  const response = await fetch(`/internal/v1/appeals/${appealId}/deny`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
}

export async function quarantine(videoId: string, reason: string): Promise<void> {
  const response = await fetch(`/internal/v1/videos/${videoId}/quarantine`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
}

export async function restore(videoId: string): Promise<void> {
  const response = await fetch(`/internal/v1/videos/${videoId}/restore`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
}

export async function removeVideo(videoId: string, reason: string): Promise<void> {
  const response = await fetch(`/internal/v1/videos/${videoId}/remove`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
}

export type PlaybackSessionResponse = {
  videoId: string;
  processingVersion: number;
  mode: string;
  expiresAt: string;
};

/** Admin-only; lets a moderator watch a video before approving/rejecting it. */
export async function createModeratorPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  const response = await fetch(`/internal/v1/videos/${videoId}/moderator-playback-session`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}
