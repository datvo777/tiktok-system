// Same-origin via the Vite proxy, same as web/ (brief section 12.1). `credentials`
// is deliberately left at its default.
//
// Responses are validated at this boundary rather than cast — see ./http. The
// admin surface needs the 401/403 distinction most: 401 means "not signed in",
// 403 means "signed in without the ADMIN role", and they call for different
// messages.

import { ApiError, jsonBody, request, requestNoContent, s, type Infer } from '@short/shared';

export { ApiError, ContractError } from '@short/shared';

/**
 * The bearer token in the response is deliberately not read: the HttpOnly
 * session cookie set alongside it authenticates every request this app makes.
 */
const loginSchema = s.object({ accountId: s.string, expiresAt: s.string });
export type LoginResponse = Infer<typeof loginSchema>;

const pendingVideoSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  state: s.string,
  createdAt: s.string,
});
export type PendingVideo = Infer<typeof pendingVideoSchema>;

const pendingPageSchema = s.object({
  items: s.array(pendingVideoSchema),
  nextCursor: s.nullable(s.string),
});
export type PendingVideoPage = Infer<typeof pendingPageSchema>;

export async function login(email: string, password: string): Promise<LoginResponse> {
  return request(
    '/api/v1/auth/login',
    (payload) => loginSchema.parse('login', payload),
    jsonBody({ email, password }),
  );
}

const meSchema = s.object({
  accountId: s.string,
  displayName: s.string,
  state: s.string,
  roles: s.array(s.string),
});
export type MeResponse = Infer<typeof meSchema>;

/**
 * Checked once on load: the session cookie survives a page refresh even
 * though React state doesn't, so this is how the app tells "actually signed
 * out" apart from "just reloaded the page." Throws (401) if there's no valid
 * session.
 */
export async function getMe(): Promise<MeResponse> {
  return request('/api/v1/auth/me', (payload) => meSchema.parse('me', payload));
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
  return request(`/internal/v1/videos/pending?${params}`, (payload) =>
    pendingPageSchema.parse('pending', payload),
  );
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

const pendingAppealSchema = s.object({
  videoId: s.string,
  state: s.string,
  reason: s.nullable(s.string),
  decisionReason: s.nullable(s.string),
});
export type PendingAppeal = Infer<typeof pendingAppealSchema>;

/** Milestone 6 (brief section 18). {@code appealId} is the video's id. */
export async function listPendingAppeals(): Promise<PendingAppeal[]> {
  return request('/internal/v1/appeals/pending', (payload) =>
    s.array(pendingAppealSchema).parse('appeals', payload),
  );
}

export async function approveAppeal(appealId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/appeals/${appealId}/approve`, jsonBody({ reason }));
}

export async function denyAppeal(appealId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/appeals/${appealId}/deny`, jsonBody({ reason }));
}

// ------------------------------------------------------------------- reports
//
// Viewer-submitted reports, which the platform previously had no channel for at
// all: moderation reviewed every upload on the way in and had takedown tools
// afterwards, but the people who actually encounter a problem had no way to say
// so.

const reportSubjectType = s.oneOf('VIDEO', 'ACCOUNT', 'COMMENT');
export type ReportSubjectType = Infer<typeof reportSubjectType>;

const openReportSchema = s.object({
  reportId: s.string,
  subjectType: reportSubjectType,
  subjectId: s.string,
  reason: s.string,
  detail: s.nullable(s.string),
  createdAt: s.string,
  /** How many people have reported this same subject — the triage signal. */
  openReportsForSubject: s.number,
});
export type OpenReport = Infer<typeof openReportSchema>;

const reportListSchema = s.object({ items: s.array(openReportSchema) });

export type ReportResolution = 'ACTIONED' | 'DISMISSED' | 'ABUSIVE_REPORT';

export async function listOpenReports(limit = 50): Promise<OpenReport[]> {
  return request(
    `/internal/v1/reports/open?limit=${limit}`,
    (payload) => reportListSchema.parse('reports', payload).items,
  );
}

export async function resolveReport(
  reportId: string,
  resolution: ReportResolution,
  note: string,
): Promise<void> {
  await request(
    `/internal/v1/reports/${reportId}/resolve`,
    () => undefined,
    jsonBody({ resolution, note: note.trim() || null }),
  );
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
const videoDetailSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  creatorDisplayName: s.string,
  title: s.nullable(s.string),
  description: s.nullable(s.string),
  processingState: s.string,
  moderationState: s.string,
  publicationState: s.string,
  assetLifecycleState: s.string,
  legalServingState: s.string,
  isVideoEligible: s.boolean,
  updatedAt: s.string,
});
export type VideoDetail = Infer<typeof videoDetailSchema>;

/** A 404 here means no eligibility row exists for that id yet (or ever) -- not necessarily a typo'd id. */
export async function getVideoDetail(videoId: string): Promise<VideoDetail> {
  return request(`/internal/v1/videos/${videoId}`, (payload) => videoDetailSchema.parse('videoDetail', payload));
}

/** Public search (creator name, title, or description) reused here for admin lookup by keyword. */
const videoSearchHitSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  creatorDisplayName: s.string,
  title: s.nullable(s.string),
  publishedAt: s.string,
});
export type VideoSearchHit = Infer<typeof videoSearchHitSchema>;

const videoSearchSchema = s.object({ results: s.array(videoSearchHitSchema) });

export async function searchVideos(query: string): Promise<VideoSearchHit[]> {
  return request(
    `/api/v1/search?q=${encodeURIComponent(query)}`,
    (payload) => videoSearchSchema.parse('search', payload).results,
  );
}

const adminAccountSchema = s.object({
  accountId: s.string,
  email: s.string,
  displayName: s.string,
  state: s.string,
  roles: s.array(s.string),
  createdAt: s.string,
});
export type AdminAccount = Infer<typeof adminAccountSchema>;

const adminAccountListSchema = s.object({ items: s.array(adminAccountSchema) });

/** Substring match on email, newest accounts first, capped server-side at 50. */
export async function searchAccounts(query: string): Promise<AdminAccount[]> {
  return request(
    `/internal/v1/accounts?q=${encodeURIComponent(query)}`,
    (payload) => adminAccountListSchema.parse('accounts', payload).items,
  );
}

export async function suspendAccount(accountId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/accounts/${accountId}/suspend`, jsonBody({ reason }));
}

export async function reinstateAccount(accountId: string, reason: string): Promise<void> {
  await requestNoContent(`/internal/v1/accounts/${accountId}/reinstate`, jsonBody({ reason }));
}

const playbackSessionSchema = s.object({
  videoId: s.string,
  processingVersion: s.number,
  mode: s.string,
  expiresAt: s.string,
});
export type PlaybackSessionResponse = Infer<typeof playbackSessionSchema>;

/** Admin-only; lets a moderator watch a video before approving/rejecting it. */
export async function createModeratorPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/internal/v1/videos/${videoId}/moderator-playback-session`,
    (payload) => playbackSessionSchema.parse('moderatorSession', payload),
    { method: 'POST' },
  );
}
