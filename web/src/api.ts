// Every request is same-origin thanks to the Vite proxy, so the browser attaches
// the SameSite=Lax session cookie automatically. `credentials` is deliberately
// left at its default: `include` is for cross-origin calls, and needing it here
// would mean the proxy is misconfigured.
//
// Responses are validated at this boundary rather than cast — see ./http.

import { jsonBody, request, requestNoContent, s, type Infer } from '@short/shared';

export { ApiError, ContractError } from '@short/shared';

const loginSchema = s.object({ accountId: s.string, expiresAt: s.string });
export type LoginResponse = Infer<typeof loginSchema>;

export async function register(email: string, password: string, displayName: string): Promise<void> {
  await requestNoContent('/api/v1/accounts', jsonBody({ email, password, displayName }));
}

/**
 * The response also carries a bearer token. It is deliberately not read or
 * stored: the HttpOnly session cookie set alongside it is what authenticates
 * every request this app makes, and a copy in JavaScript memory would only add
 * somewhere for an XSS to steal it from.
 */
export async function login(email: string, password: string): Promise<LoginResponse> {
  return request('/api/v1/auth/login', (payload) => loginSchema.parse('login', payload), jsonBody({ email, password }));
}

const meSchema = s.object({
  accountId: s.string,
  displayName: s.string,
  /** The unique, mentionable username. Never null. */
  handle: s.string,
  bio: s.nullable(s.string),
  state: s.string,
  roles: s.array(s.string),
});
export type MeResponse = Infer<typeof meSchema>;

/**
 * Checked once on load: the session cookie survives a page refresh even
 * though React state doesn't, so this is how the app tells "actually signed
 * out" apart from "just reloaded the page." Throws ApiError(401) if there's no
 * valid session.
 */
export async function getMe(): Promise<MeResponse> {
  return request('/api/v1/auth/me', (payload) => meSchema.parse('me', payload));
}

/**
 * Extends the session without re-entering a password. The server revokes the old
 * token as part of issuing the new one, so this replaces the session rather than
 * adding a second live credential.
 */
/**
 * PATCH semantics: omit a field to leave it alone. Sending an empty `bio`
 * clears it -- there is no useful distinction between an empty bio and no bio.
 */
export async function updateProfile(changes: { displayName?: string; bio?: string }): Promise<void> {
  await request(
    '/api/v1/accounts/me',
    () => undefined,
    { ...jsonBody(changes), method: 'PATCH' },
  );
}

/**
 * Changes the caller's username. Its own call rather than a field on
 * `updateProfile`, because it has its own failure mode: taken.
 */
export async function changeHandle(handle: string): Promise<void> {
  await request('/api/v1/accounts/me/handle', () => undefined, {
    ...jsonBody({ handle }),
    method: 'PATCH',
  });
}

const handleAvailabilitySchema = s.object({
  handle: s.string,
  available: s.boolean,
  reason: s.nullable(s.string),
});
export type HandleAvailability = Infer<typeof handleAvailabilitySchema>;

/**
 * Answers "can I have this one?" while someone types. Never rejects a malformed
 * value with an error status -- an in-progress username is not a failure, it is
 * just not available yet -- so this returns a reason rather than throwing.
 */
export async function checkHandle(handle: string): Promise<HandleAvailability> {
  return request(`/api/v1/accounts/handle-available?handle=${encodeURIComponent(handle)}`, (payload) =>
    handleAvailabilitySchema.parse('handleAvailability', payload),
  );
}

/** The current password is required: a live session proves the browser, not the person. */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await requestNoContent('/api/v1/accounts/me/password', jsonBody({ currentPassword, newPassword }));
}

export async function refreshSession(): Promise<LoginResponse> {
  return request('/api/v1/auth/refresh', (payload) => loginSchema.parse('refresh', payload), { method: 'POST' });
}

export async function logout(): Promise<void> {
  // Reports failure now: logout revokes the session server-side, so silently
  // swallowing an error would leave the user believing they had signed out.
  await requestNoContent('/api/v1/auth/logout', { method: 'POST' });
}

const createUploadSchema = s.object({
  uploadId: s.string,
  videoId: s.string,
  uploadUrl: s.string,
  formFields: s.stringMap(),
  maxBytes: s.number,
  expiresAt: s.string,
});
export type CreateUploadResponse = Infer<typeof createUploadSchema>;

const uploadStatus = s.oneOf('PENDING', 'COMPLETED', 'EXPIRED');
export type UploadStatus = Infer<typeof uploadStatus>;

const uploadSchema = s.object({
  uploadId: s.string,
  videoId: s.string,
  status: uploadStatus,
  completedSizeBytes: s.nullable(s.number),
});
export type UploadResponse = Infer<typeof uploadSchema>;

const processingState = s.oneOf(
  'CREATED',
  'UPLOADING',
  'UPLOADED',
  'TRANSCODING',
  'READY',
  'FAILED',
  'EXPIRED',
);
export type ProcessingState = Infer<typeof processingState>;

const assetLifecycleState = s.oneOf(
  'ACTIVE',
  'REJECTED_RETAINED',
  'DELETE_SCHEDULED',
  'DELETION_IN_PROGRESS',
  'QUARANTINED',
  'DELETED',
  'RESTORING',
);
export type AssetLifecycleState = Infer<typeof assetLifecycleState>;

const videoSchema = s.object({
  videoId: s.string,
  processingState,
  processingVersion: s.nullable(s.number),
  durabilityState: s.oneOf('PENDING', 'DURABLE'),
  assetLifecycleState,
  failureClass: s.nullable(s.string),
  pollAfterMs: s.nullable(s.number),
});
export type VideoResponse = Infer<typeof videoSchema>;

const playbackMode = s.oneOf('OWNER_PREVIEW', 'PUBLIC', 'MODERATOR_PREVIEW');
export type PlaybackMode = Infer<typeof playbackMode>;

const playbackSessionSchema = s.object({
  videoId: s.string,
  processingVersion: s.number,
  mode: playbackMode,
  expiresAt: s.string,
});
export type PlaybackSessionResponse = Infer<typeof playbackSessionSchema>;

/** Creates the upload session; the video draft is created in the same transaction (brief section 7.1). */
export async function createUpload(title: string, description: string): Promise<CreateUploadResponse> {
  return request(
    '/api/v1/uploads',
    (payload) => createUploadSchema.parse('createUpload', payload),
    jsonBody({ title, description: description || null }),
  );
}

/**
 * Posts the file straight to MinIO with the presigned policy. Deliberately
 * cross-origin and cookie-free — this request must not set `credentials`
 * (brief section 12.1).
 *
 * <p>Field order matters: S3-style POST requires every policy field to precede
 * the file part, and MinIO rejects the request outright ("the name of the
 * uploaded key is missing") if `key` arrives after it. The policy caps the body
 * size, so an oversized file is refused by the object store with EntityTooLarge
 * rather than being stored and rejected later.
 */
export async function postToPresignedUrl(
  session: CreateUploadResponse,
  file: File,
  options?: {
    /** Fraction uploaded, 0..1. Only called while the total length is known. */
    onProgress?: (fraction: number) => void;
    signal?: AbortSignal;
  },
): Promise<void> {
  const form = new FormData();
  for (const [name, value] of Object.entries(session.formFields)) {
    form.append(name, value);
  }
  form.append('file', file);

  // XMLHttpRequest rather than fetch, for the one thing fetch still cannot do:
  // report upload progress. A `fetch` body is opaque once handed over, so a
  // 200 MB upload over a phone connection showed a button reading "Uploading…"
  // for several minutes with no percentage, no speed and no way to stop.
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', session.uploadUrl, true);

    if (options?.onProgress) {
      xhr.upload.addEventListener('progress', (event) => {
        // `lengthComputable` is false for a body of unknown size; reporting a
        // fraction of an unknown total would only produce a lying bar.
        if (event.lengthComputable && event.total > 0) {
          options.onProgress?.(event.loaded / event.total);
        }
      });
    }

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
        return;
      }
      const detail = xhr.status === 400 ? ' (file may exceed the allowed size)' : '';
      reject(new Error(`Upload to storage failed: HTTP ${xhr.status}${detail}`));
    });
    xhr.addEventListener('error', () => reject(new Error('Upload to storage failed: the network request did not complete')));
    xhr.addEventListener('timeout', () => reject(new Error('Upload to storage timed out')));
    xhr.addEventListener('abort', () => reject(new UploadCancelled()));

    if (options?.signal) {
      if (options.signal.aborted) {
        reject(new UploadCancelled());
        return;
      }
      options.signal.addEventListener('abort', () => xhr.abort(), { once: true });
    }

    // Deliberately no credentials and no headers: this request is cross-origin
    // to the object store and must not carry the session cookie (brief 12.1).
    xhr.send(form);
  });
}

/** Distinguishes "the person pressed Cancel" from a genuine upload failure. */
export class UploadCancelled extends Error {
  constructor() {
    super('Upload cancelled');
    this.name = 'UploadCancelled';
  }
}

export async function completeUpload(uploadId: string): Promise<UploadResponse> {
  return request(
    `/api/v1/uploads/${uploadId}/complete`,
    (payload) => uploadSchema.parse('completeUpload', payload),
    { method: 'POST' },
  );
}

export async function getVideo(videoId: string): Promise<VideoResponse> {
  return request(`/api/v1/videos/${videoId}`, (payload) => videoSchema.parse('video', payload));
}

function parsePlaybackSession(context: string, payload: unknown): PlaybackSessionResponse {
  return playbackSessionSchema.parse(context, payload);
}

/** Owner-only; sets the path-scoped sv_playback cookie the gateway requires (brief section 8). */
export async function createPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/api/v1/videos/${videoId}/preview-playback-session`,
    (payload) => parsePlaybackSession('previewSession', payload),
    { method: 'POST' },
  );
}

const publicationState = s.oneOf('PRIVATE', 'PUBLISH_PENDING', 'PUBLISHED', 'SUSPENDED', 'REMOVED');
export type PublicationState = Infer<typeof publicationState>;

const publicationSchema = s.object({ videoId: s.string, state: publicationState, intent: s.boolean });
export type PublicationResponse = Infer<typeof publicationSchema>;

/** Owner-only; the coordinator publishes only once processing and moderation both agree (brief section 8). */
export async function publishVideo(videoId: string): Promise<PublicationResponse> {
  return request(
    `/api/v1/videos/${videoId}/publish`,
    (payload) => publicationSchema.parse('publish', payload),
    { method: 'POST' },
  );
}

/** Requires the full public eligibility invariant to pass (brief section 8); denies until approved and published. */
/** The reversible inverse of publish: the video leaves the feed and returns to PRIVATE. */
export async function unpublishVideo(videoId: string): Promise<PublicationResponse> {
  return request(
    `/api/v1/videos/${videoId}/unpublish`,
    (payload) => publicationSchema.parse('unpublish', payload),
    { method: 'POST' },
  );
}

/** Permanent, and owner-only. Schedules the video's assets for deletion. */
export async function deleteVideo(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}`, { method: 'DELETE' });
}

export async function createPublicSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/api/v1/videos/${videoId}/public-playback-session`,
    (payload) => parsePlaybackSession('publicSession', payload),
    { method: 'POST' },
  );
}

const appealState = s.oneOf('NONE', 'UNDER_APPEAL', 'REVIEWING', 'APPROVED', 'DENIED', 'ESCALATED');
export type AppealState = Infer<typeof appealState>;

const appealSchema = s.object({
  videoId: s.string,
  state: appealState,
  reason: s.nullable(s.string),
  decisionReason: s.nullable(s.string),
});
export type AppealResponse = Infer<typeof appealSchema>;

function parseAppeal(context: string, payload: unknown): AppealResponse {
  return appealSchema.parse(context, payload);
}

/** Owner-only; only accepted while moderation state is REJECTED (brief section 18, Milestone 6). */
export async function submitAppeal(videoId: string, reason: string): Promise<AppealResponse> {
  return request(`/api/v1/videos/${videoId}/appeals`, (payload) => parseAppeal('appeal', payload), jsonBody({ reason }));
}

/** Owner-only; NONE means no appeal has ever been submitted for this video. */
export async function getAppealStatus(videoId: string): Promise<AppealResponse> {
  return request(`/api/v1/videos/${videoId}/appeals`, (payload) => parseAppeal('appeal', payload));
}

const videoSummarySchema = s.object({
  videoId: s.string,
  title: s.nullable(s.string),
  processingState,
  assetLifecycleState,
  /** Null while the projection has not caught up; treat as an unpublished draft. */
  publicationState: s.nullable(publicationState),
  createdAt: s.string,
});
export type VideoSummary = Infer<typeof videoSummarySchema>;

const videoListSchema = s.object({
  page: s.number,
  items: s.array(videoSummarySchema),
  hasMore: s.boolean,
});
export type VideoListResponse = Infer<typeof videoListSchema>;

/** The caller's own videos, most recent first — used by the "My videos" panel. */
export async function getMyVideos(page = 0): Promise<VideoListResponse> {
  return request(`/api/v1/videos?page=${page}`, (payload) => videoListSchema.parse('videoList', payload));
}

const feedItemSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  // Nullable: a video published before this feature shipped has no metadata row.
  title: s.nullable(s.string),
  description: s.nullable(s.string),
});
export type FeedItem = Infer<typeof feedItemSchema>;

const feedSchema = s.object({ page: s.number, items: s.array(feedItemSchema), hasMore: s.boolean });
export type FeedResponse = Infer<typeof feedSchema>;

/** FOR_YOU ranks everything the viewer may see; FOLLOWING keeps only creators they follow. */
export type FeedScope = 'FOR_YOU' | 'FOLLOWING';

export async function getFeed(page = 0, scope: FeedScope = 'FOR_YOU'): Promise<FeedResponse> {
  return request(`/api/v1/feed?page=${page}&scope=${scope}`, (payload) => feedSchema.parse('feed', payload));
}

/**
 * One video by id, in the same shape the feed serves it. This is what a shared
 * link and a notification resolve against — without it a URL naming a video had
 * nothing to fetch, so the app could only ever show the generic ranking.
 *
 * <p>Throws ApiError(404) when the video is missing, not currently eligible, or
 * revoked; the server deliberately does not distinguish those.
 */
export async function getFeedItem(videoId: string): Promise<FeedItem> {
  return request(`/api/v1/feed/videos/${videoId}`, (payload) => feedItemSchema.parse('feedItem', payload));
}

/**
 * The thumbnail for a video, as a URL an `<img>` or a `poster` attribute can
 * use directly.
 *
 * <p>Deliberately not a fetch: the browser's own image cache is what stops a
 * twenty-tile grid re-downloading on every render. The processing version is
 * resolved server-side, so this URL stays correct across a reprocess.
 *
 * <p>404s for anything the worker could not produce a still for — including
 * every video processed before thumbnails existed. Callers render their
 * placeholder on error rather than checking first.
 */
export function posterUrl(videoId: string): string {
  return `/api/v1/videos/${videoId}/poster`;
}

export async function likeVideo(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/likes`, { method: 'POST' });
}

export async function unlikeVideo(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/likes`, { method: 'DELETE' });
}

/**
 * Records that the viewer watched this video. Called once per video once they
 * have watched enough for it to count -- not on every `timeupdate`.
 *
 * <p>Best-effort by design: a failed view record is not worth telling anyone
 * about, and must never interrupt playback.
 */
export async function recordView(videoId: string, watchedMs: number, completed: boolean): Promise<void> {
  await requestNoContent(
    `/api/v1/videos/${videoId}/views`,
    jsonBody({ watchedMs: Math.round(watchedMs), completed }),
  );
}

export async function recordShare(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/shares`, { method: 'POST' });
}

const videoCountsSchema = s.object({
  likeCount: s.number,
  commentCount: s.number,
  shareCount: s.number,
  /** Distinct viewers, not total plays. */
  viewCount: s.number,
  liked: s.boolean,
});
export type VideoCounts = Infer<typeof videoCountsSchema>;

export async function getVideoCounts(videoId: string): Promise<VideoCounts> {
  return request(`/api/v1/videos/${videoId}/counts`, (payload) => videoCountsSchema.parse('counts', payload));
}

const commentSchema = s.object({
  commentId: s.string,
  videoId: s.string,
  accountId: s.string,
  /** Null on the response to posting, where the author is the caller. */
  authorDisplayName: s.nullable(s.string),
  /** Null on the response to posting, like the display name beside it. */
  authorHandle: s.nullable(s.string),
  body: s.string,
  createdAt: s.string,
  parentCommentId: s.nullable(s.string),
  replyCount: s.number,
});
export type CommentResponse = Infer<typeof commentSchema>;

function parseComment(context: string, payload: unknown): CommentResponse {
  return commentSchema.parse(context, payload);
}

/**
 * @property nextCursor pass back to fetch the following page; null means this
 *   was the last one. The list was previously capped at 200 server-side with no
 *   cursor and no signal, so a client could not tell a finished list from a
 *   truncated one.
 */
const commentPageSchema = s.object({
  items: s.array(commentSchema),
  nextCursor: s.nullable(s.string),
});
export type CommentPage = Infer<typeof commentPageSchema>;

function parseCommentPage(context: string, payload: unknown): CommentPage {
  return commentPageSchema.parse(context, payload);
}

export async function commentOnVideo(videoId: string, body: string): Promise<CommentResponse> {
  return request(`/api/v1/videos/${videoId}/comments`, (payload) => parseComment('comment', payload), jsonBody({ body }));
}

export async function listComments(videoId: string, cursor?: string | null): Promise<CommentPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
  return request(`/api/v1/videos/${videoId}/comments${query}`, (payload) => parseCommentPage('comments', payload));
}

/** Allowed to the comment's author and to the owner of the video it sits on. */
export async function deleteComment(videoId: string, commentId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/comments/${commentId}`, { method: 'DELETE' });
}

export async function replyToComment(videoId: string, commentId: string, body: string): Promise<CommentResponse> {
  return request(
    `/api/v1/videos/${videoId}/comments/${commentId}/replies`,
    (payload) => parseComment('reply', payload),
    jsonBody({ body }),
  );
}

export async function listReplies(
  videoId: string,
  commentId: string,
  cursor?: string | null,
): Promise<CommentPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
  return request(
    `/api/v1/videos/${videoId}/comments/${commentId}/replies${query}`,
    (payload) => parseCommentPage('replies', payload),
  );
}

/** No accountState: only eligible creators have a profile, so the field could only leak suspensions. */
const creatorProfileSchema = s.object({
  accountId: s.string,
  displayName: s.string,
  handle: s.string,
  bio: s.nullable(s.string),
  followerCount: s.number,
  followingCount: s.number,
  /** Viewer-relative: whether the caller follows this creator. False when signed out. */
  following: s.boolean,
});
export type CreatorProfile = Infer<typeof creatorProfileSchema>;

export async function getCreatorProfile(creatorId: string): Promise<CreatorProfile> {
  return request(`/api/v1/creators/${creatorId}`, (payload) => creatorProfileSchema.parse('creator', payload));
}

export async function followCreator(creatorId: string): Promise<void> {
  await requestNoContent(`/api/v1/creators/${creatorId}/follow`, { method: 'POST' });
}

export async function unfollowCreator(creatorId: string): Promise<void> {
  await requestNoContent(`/api/v1/creators/${creatorId}/follow`, { method: 'DELETE' });
}

const creatorVideoSchema = s.object({
  videoId: s.string,
  title: s.nullable(s.string),
  description: s.nullable(s.string),
  publishedAt: s.string,
});
export type CreatorVideo = Infer<typeof creatorVideoSchema>;

const creatorVideoListSchema = s.object({
  page: s.number,
  items: s.array(creatorVideoSchema),
  hasMore: s.boolean,
});
export type CreatorVideoListResponse = Infer<typeof creatorVideoListSchema>;

/** A creator's published videos, newest first (backed by the search index). */
export async function getCreatorVideos(creatorId: string, page = 0): Promise<CreatorVideoListResponse> {
  return request(`/api/v1/creators/${creatorId}/videos?page=${page}`, (payload) =>
    creatorVideoListSchema.parse('creatorVideos', payload),
  );
}

const notificationSchema = s.object({
  notificationId: s.string,
  type: s.string,
  message: s.string,
  relatedVideoId: s.nullable(s.string),
  read: s.boolean,
  createdAt: s.string,
});
export type NotificationItem = Infer<typeof notificationSchema>;

/**
 * @property unreadCount across the whole account, not just this page — the inbox
 *   badge previously counted only the rows the client happened to have fetched.
 */
const notificationPageSchema = s.object({
  items: s.array(notificationSchema),
  nextCursor: s.nullable(s.string),
  unreadCount: s.number,
});
export type NotificationPage = Infer<typeof notificationPageSchema>;

export async function getNotifications(cursor?: string | null): Promise<NotificationPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
  return request(`/api/v1/notifications${query}`, (payload) =>
    notificationPageSchema.parse('notifications', payload),
  );
}

/**
 * One request instead of one per notification, and it clears the whole account
 * rather than only what is on screen.
 */
export async function markAllNotificationsRead(): Promise<number> {
  return request(
    '/api/v1/notifications/read-all',
    (payload) => s.object({ markedRead: s.number }).parse('markAllRead', payload).markedRead,
    { method: 'POST' },
  );
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  await requestNoContent(`/api/v1/notifications/${notificationId}/read`, { method: 'POST' });
}

const searchHitSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  // Validated, not assumed: SearchPanel slices this string, and an absent field
  // previously threw at render into no error boundary.
  creatorDisplayName: s.string,
  /** Empty for documents indexed before handles existed. */
  creatorHandle: s.string,
  title: s.nullable(s.string),
  description: s.nullable(s.string),
  publishedAt: s.string,
});
export type SearchHit = Infer<typeof searchHitSchema>;

const searchSchema = s.object({
  query: s.string,
  page: s.number,
  results: s.array(searchHitSchema),
  hasMore: s.boolean,
});
export type SearchResponse = Infer<typeof searchSchema>;

/** Milestone 7: matches published videos by creator display name, title, or description. */
export async function search(query: string, page = 0): Promise<SearchResponse> {
  return request(`/api/v1/search?q=${encodeURIComponent(query)}&page=${page}`, (payload) =>
    searchSchema.parse('search', payload),
  );
}

// -------------------------------------------------------------------- reports
//
// Filing a report changes nothing about a video's visibility -- it is evidence a
// moderator reads. Anything else would let a handful of coordinated accounts
// take down whatever they liked.

export type ReportReason =
  | 'SEXUAL_CONTENT'
  | 'VIOLENCE_OR_GORE'
  | 'HATE_OR_HARASSMENT'
  | 'DANGEROUS_ACTS'
  | 'MISINFORMATION'
  | 'SPAM_OR_SCAM'
  | 'INTELLECTUAL_PROPERTY'
  | 'CHILD_SAFETY'
  | 'OTHER';

/** Shown in the order a viewer is most likely to need them. */
export const REPORT_REASONS: { value: ReportReason; label: string }[] = [
  { value: 'SEXUAL_CONTENT', label: 'Nudity or sexual content' },
  { value: 'VIOLENCE_OR_GORE', label: 'Violence or graphic content' },
  { value: 'HATE_OR_HARASSMENT', label: 'Hate speech or harassment' },
  { value: 'CHILD_SAFETY', label: 'Child safety' },
  { value: 'DANGEROUS_ACTS', label: 'Dangerous acts' },
  { value: 'MISINFORMATION', label: 'Harmful misinformation' },
  { value: 'SPAM_OR_SCAM', label: 'Spam or scam' },
  { value: 'INTELLECTUAL_PROPERTY', label: 'Copyright or trademark' },
  { value: 'OTHER', label: 'Something else' },
];

export type ReportSubjectType = 'VIDEO' | 'ACCOUNT' | 'COMMENT';

/**
 * Idempotent per reporter and subject: reporting the same thing twice while the
 * first report is still open returns that report rather than filing a second.
 */
export async function submitReport(
  subjectType: ReportSubjectType,
  subjectId: string,
  reason: ReportReason,
  detail?: string,
): Promise<void> {
  await request(
    `/api/v1/reports/${subjectId}`,
    () => undefined,
    jsonBody({ subjectType, reason, detail: detail?.trim() || null }),
  );
}

// ------------------------------------------------------------------ favorites
//
// Collections are private to their owner, so every path here is implicitly
// scoped to the signed-in account -- there is no collection id in the URL that
// belongs to anyone else.

const favoriteCollectionSchema = s.object({
  collectionId: s.string,
  name: s.string,
  itemCount: s.number,
  /** Only meaningful from listCollectionsForVideo: does this collection hold that video? */
  containsVideo: s.boolean,
  createdAt: s.string,
  updatedAt: s.string,
});
export type FavoriteCollection = Infer<typeof favoriteCollectionSchema>;

const savedVideoSchema = s.object({
  videoId: s.string,
  creatorId: s.string,
  creatorDisplayName: s.string,
  title: s.nullable(s.string),
  description: s.nullable(s.string),
  savedAt: s.string,
});
export type SavedVideo = Infer<typeof savedVideoSchema>;

const collectionDetailSchema = s.object({
  collection: favoriteCollectionSchema,
  items: s.array(savedVideoSchema),
  /** Saved videos that are no longer playable -- counted, not listed. */
  unavailableCount: s.number,
});
export type FavoriteCollectionDetail = Infer<typeof collectionDetailSchema>;

const collectionListSchema = s.object({ items: s.array(favoriteCollectionSchema) });

function parseCollection(context: string, raw: unknown): FavoriteCollection {
  return favoriteCollectionSchema.parse(context, raw);
}

function parseCollectionList(payload: unknown): FavoriteCollection[] {
  return collectionListSchema.parse('favorites', payload).items;
}

export async function getCollections(): Promise<FavoriteCollection[]> {
  return request('/api/v1/favorites/collections', parseCollectionList);
}

/** The save-picker's list: same collections, each flagged with whether it already holds the video. */
export async function getCollectionsForVideo(videoId: string): Promise<FavoriteCollection[]> {
  return request(`/api/v1/favorites/collections?videoId=${encodeURIComponent(videoId)}`, parseCollectionList);
}

export async function getCollection(collectionId: string): Promise<FavoriteCollectionDetail> {
  return request(`/api/v1/favorites/collections/${collectionId}`, (payload) =>
    collectionDetailSchema.parse('collection', payload),
  );
}

export async function createCollection(name: string): Promise<FavoriteCollection> {
  return request('/api/v1/favorites/collections', (payload) => parseCollection('collection', payload), jsonBody({ name }));
}

export async function renameCollection(collectionId: string, name: string): Promise<FavoriteCollection> {
  return request(
    `/api/v1/favorites/collections/${collectionId}`,
    (payload) => parseCollection('collection', payload),
    { ...jsonBody({ name }), method: 'PATCH' },
  );
}

export async function deleteCollection(collectionId: string): Promise<void> {
  await requestNoContent(`/api/v1/favorites/collections/${collectionId}`, { method: 'DELETE' });
}

/** Omit collectionId to save into the default collection, created on the first save. */
export async function saveVideo(videoId: string, collectionId?: string): Promise<FavoriteCollection> {
  return request(
    `/api/v1/favorites/videos/${videoId}`,
    (payload) => parseCollection('collection', payload),
    jsonBody({ collectionId: collectionId ?? null }),
  );
}

export async function unsaveVideo(collectionId: string, videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/favorites/collections/${collectionId}/videos/${videoId}`, { method: 'DELETE' });
}

/** Whether the viewer has this video in any collection -- the feed's bookmark state. */
export async function isVideoSaved(videoId: string): Promise<boolean> {
  return request(
    `/api/v1/favorites/videos/${videoId}`,
    (payload) => s.object({ saved: s.boolean }).parse('saved', payload).saved,
  );
}
