// Every request is same-origin thanks to the Vite proxy, so the browser attaches
// the SameSite=Lax session cookie automatically. `credentials` is deliberately
// left at its default: `include` is for cross-origin calls, and needing it here
// would mean the proxy is misconfigured.
//
// Responses are validated at this boundary rather than cast — see ./http.

import {
  arr,
  bool,
  jsonBody,
  nullableNum,
  nullableStr,
  num,
  obj,
  oneOf,
  request,
  requestNoContent,
  str,
  strMap,
} from './http';

export { ApiError, ContractError } from './http';

export type LoginResponse = {
  accountId: string;
  expiresAt: string;
};

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
 * out" apart from "just reloaded the page." Throws ApiError(401) if there's no
 * valid session.
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

/**
 * Extends the session without re-entering a password. The server revokes the old
 * token as part of issuing the new one, so this replaces the session rather than
 * adding a second live credential.
 */
export async function refreshSession(): Promise<LoginResponse> {
  return request(
    '/api/v1/auth/refresh',
    (payload) => {
      const o = obj('refresh', payload);
      return { accountId: str('refresh', o, 'accountId'), expiresAt: str('refresh', o, 'expiresAt') };
    },
    { method: 'POST' },
  );
}

export async function logout(): Promise<void> {
  // Reports failure now: logout revokes the session server-side, so silently
  // swallowing an error would leave the user believing they had signed out.
  await requestNoContent('/api/v1/auth/logout', { method: 'POST' });
}

export type CreateUploadResponse = {
  uploadId: string;
  videoId: string;
  uploadUrl: string;
  formFields: Record<string, string>;
  maxBytes: number;
  expiresAt: string;
};

export type UploadStatus = 'PENDING' | 'COMPLETED' | 'EXPIRED';
const UPLOAD_STATUSES = ['PENDING', 'COMPLETED', 'EXPIRED'] as const;

export type UploadResponse = {
  uploadId: string;
  videoId: string;
  status: UploadStatus;
  completedSizeBytes: number | null;
};

export type ProcessingState =
  | 'CREATED'
  | 'UPLOADING'
  | 'UPLOADED'
  | 'TRANSCODING'
  | 'READY'
  | 'FAILED'
  | 'EXPIRED';
const PROCESSING_STATES = [
  'CREATED',
  'UPLOADING',
  'UPLOADED',
  'TRANSCODING',
  'READY',
  'FAILED',
  'EXPIRED',
] as const;

export type AssetLifecycleState =
  | 'ACTIVE'
  | 'REJECTED_RETAINED'
  | 'DELETE_SCHEDULED'
  | 'DELETION_IN_PROGRESS'
  | 'QUARANTINED'
  | 'DELETED'
  | 'RESTORING';
const ASSET_LIFECYCLE_STATES = [
  'ACTIVE',
  'REJECTED_RETAINED',
  'DELETE_SCHEDULED',
  'DELETION_IN_PROGRESS',
  'QUARANTINED',
  'DELETED',
  'RESTORING',
] as const;

export type VideoResponse = {
  videoId: string;
  processingState: ProcessingState;
  processingVersion: number | null;
  durabilityState: 'PENDING' | 'DURABLE';
  assetLifecycleState: AssetLifecycleState;
  failureClass: string | null;
  pollAfterMs: number | null;
};

export type PlaybackMode = 'OWNER_PREVIEW' | 'PUBLIC' | 'MODERATOR_PREVIEW';
const PLAYBACK_MODES = ['OWNER_PREVIEW', 'PUBLIC', 'MODERATOR_PREVIEW'] as const;

export type PlaybackSessionResponse = {
  videoId: string;
  processingVersion: number;
  mode: PlaybackMode;
  expiresAt: string;
};

/** Creates the upload session; the video draft is created in the same transaction (brief section 7.1). */
export async function createUpload(title: string, description: string): Promise<CreateUploadResponse> {
  return request(
    '/api/v1/uploads',
    (payload) => {
      const o = obj('createUpload', payload);
      return {
        uploadId: str('createUpload', o, 'uploadId'),
        videoId: str('createUpload', o, 'videoId'),
        uploadUrl: str('createUpload', o, 'uploadUrl'),
        formFields: strMap('createUpload', o, 'formFields'),
        maxBytes: num('createUpload', o, 'maxBytes'),
        expiresAt: str('createUpload', o, 'expiresAt'),
      };
    },
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
): Promise<void> {
  const form = new FormData();
  for (const [name, value] of Object.entries(session.formFields)) {
    form.append(name, value);
  }
  form.append('file', file);

  const response = await fetch(session.uploadUrl, { method: 'POST', body: form });
  if (!response.ok) {
    const detail = response.status === 400 ? ' (file may exceed the allowed size)' : '';
    throw new Error(`Upload to storage failed: HTTP ${response.status}${detail}`);
  }
}

export async function completeUpload(uploadId: string): Promise<UploadResponse> {
  return request(
    `/api/v1/uploads/${uploadId}/complete`,
    (payload) => {
      const o = obj('completeUpload', payload);
      return {
        uploadId: str('completeUpload', o, 'uploadId'),
        videoId: str('completeUpload', o, 'videoId'),
        status: oneOf('completeUpload', o, 'status', UPLOAD_STATUSES),
        completedSizeBytes: nullableNum('completeUpload', o, 'completedSizeBytes'),
      };
    },
    { method: 'POST' },
  );
}

export async function getVideo(videoId: string): Promise<VideoResponse> {
  return request(`/api/v1/videos/${videoId}`, (payload) => {
    const o = obj('video', payload);
    return {
      videoId: str('video', o, 'videoId'),
      processingState: oneOf('video', o, 'processingState', PROCESSING_STATES),
      processingVersion: nullableNum('video', o, 'processingVersion'),
      durabilityState: oneOf('video', o, 'durabilityState', ['PENDING', 'DURABLE'] as const),
      assetLifecycleState: oneOf('video', o, 'assetLifecycleState', ASSET_LIFECYCLE_STATES),
      failureClass: nullableStr('video', o, 'failureClass'),
      pollAfterMs: nullableNum('video', o, 'pollAfterMs'),
    };
  });
}

function parsePlaybackSession(context: string, payload: unknown): PlaybackSessionResponse {
  const o = obj(context, payload);
  return {
    videoId: str(context, o, 'videoId'),
    processingVersion: num(context, o, 'processingVersion'),
    mode: oneOf(context, o, 'mode', PLAYBACK_MODES),
    expiresAt: str(context, o, 'expiresAt'),
  };
}

/** Owner-only; sets the path-scoped sv_playback cookie the gateway requires (brief section 8). */
export async function createPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/api/v1/videos/${videoId}/preview-playback-session`,
    (payload) => parsePlaybackSession('previewSession', payload),
    { method: 'POST' },
  );
}

export type PublicationState = 'PRIVATE' | 'PUBLISH_PENDING' | 'PUBLISHED' | 'SUSPENDED' | 'REMOVED';
const PUBLICATION_STATES = ['PRIVATE', 'PUBLISH_PENDING', 'PUBLISHED', 'SUSPENDED', 'REMOVED'] as const;

export type PublicationResponse = {
  videoId: string;
  state: PublicationState;
  intent: boolean;
};

/** Owner-only; the coordinator publishes only once processing and moderation both agree (brief section 8). */
export async function publishVideo(videoId: string): Promise<PublicationResponse> {
  return request(
    `/api/v1/videos/${videoId}/publish`,
    (payload) => {
      const o = obj('publish', payload);
      return {
        videoId: str('publish', o, 'videoId'),
        state: oneOf('publish', o, 'state', PUBLICATION_STATES),
        intent: bool('publish', o, 'intent'),
      };
    },
    { method: 'POST' },
  );
}

/** Requires the full public eligibility invariant to pass (brief section 8); denies until approved and published. */
export async function createPublicSession(videoId: string): Promise<PlaybackSessionResponse> {
  return request(
    `/api/v1/videos/${videoId}/public-playback-session`,
    (payload) => parsePlaybackSession('publicSession', payload),
    { method: 'POST' },
  );
}

export type AppealState = 'NONE' | 'UNDER_APPEAL' | 'REVIEWING' | 'APPROVED' | 'DENIED' | 'ESCALATED';
const APPEAL_STATES = ['NONE', 'UNDER_APPEAL', 'REVIEWING', 'APPROVED', 'DENIED', 'ESCALATED'] as const;

export type AppealResponse = {
  videoId: string;
  state: AppealState;
  reason: string | null;
  decisionReason: string | null;
};

/** Owner-only; only accepted while moderation state is REJECTED (brief section 18, Milestone 6). */
export async function submitAppeal(videoId: string, reason: string): Promise<AppealResponse> {
  return request(
    `/api/v1/videos/${videoId}/appeals`,
    (payload) => {
      const o = obj('appeal', payload);
      return {
        videoId: str('appeal', o, 'videoId'),
        state: oneOf('appeal', o, 'state', APPEAL_STATES),
        reason: nullableStr('appeal', o, 'reason'),
        decisionReason: nullableStr('appeal', o, 'decisionReason'),
      };
    },
    jsonBody({ reason }),
  );
}

export type FeedItem = { videoId: string; creatorId: string; title: string | null; description: string | null };
export type FeedResponse = { page: number; items: FeedItem[]; hasMore: boolean };

export async function getFeed(page = 0): Promise<FeedResponse> {
  return request(`/api/v1/feed?page=${page}`, (payload) => {
    const o = obj('feed', payload);
    return {
      page: num('feed', o, 'page'),
      hasMore: bool('feed', o, 'hasMore'),
      items: arr('feed.items', o['items']).map((raw, i) => {
        const item = obj(`feed.items[${i}]`, raw);
        return {
          videoId: str(`feed.items[${i}]`, item, 'videoId'),
          creatorId: str(`feed.items[${i}]`, item, 'creatorId'),
          // Nullable: a video published before this feature shipped has no metadata row.
          title: nullableStr(`feed.items[${i}]`, item, 'title'),
          description: nullableStr(`feed.items[${i}]`, item, 'description'),
        };
      }),
    };
  });
}

export async function likeVideo(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/likes`, { method: 'POST' });
}

export async function unlikeVideo(videoId: string): Promise<void> {
  await requestNoContent(`/api/v1/videos/${videoId}/likes`, { method: 'DELETE' });
}

export type CommentResponse = {
  commentId: string;
  videoId: string;
  accountId: string;
  body: string;
  createdAt: string;
  parentCommentId: string | null;
  replyCount: number;
};

function parseComment(context: string, payload: unknown): CommentResponse {
  const o = obj(context, payload);
  return {
    commentId: str(context, o, 'commentId'),
    videoId: str(context, o, 'videoId'),
    accountId: str(context, o, 'accountId'),
    body: str(context, o, 'body'),
    createdAt: str(context, o, 'createdAt'),
    parentCommentId: nullableStr(context, o, 'parentCommentId'),
    replyCount: num(context, o, 'replyCount'),
  };
}

function parseCommentList(context: string, payload: unknown): CommentResponse[] {
  const o = obj(context, payload);
  return arr(`${context}.items`, o['items']).map((raw, i) => parseComment(`${context}.items[${i}]`, raw));
}

export async function commentOnVideo(videoId: string, body: string): Promise<CommentResponse> {
  return request(`/api/v1/videos/${videoId}/comments`, (payload) => parseComment('comment', payload), jsonBody({ body }));
}

export async function listComments(videoId: string): Promise<CommentResponse[]> {
  return request(`/api/v1/videos/${videoId}/comments`, (payload) => parseCommentList('comments', payload));
}

export async function replyToComment(videoId: string, commentId: string, body: string): Promise<CommentResponse> {
  return request(
    `/api/v1/videos/${videoId}/comments/${commentId}/replies`,
    (payload) => parseComment('reply', payload),
    jsonBody({ body }),
  );
}

export async function listReplies(videoId: string, commentId: string): Promise<CommentResponse[]> {
  return request(`/api/v1/videos/${videoId}/comments/${commentId}/replies`, (payload) => parseCommentList('replies', payload));
}

/** No accountState: only eligible creators have a profile, so the field could only leak suspensions. */
export type CreatorProfile = {
  accountId: string;
  displayName: string;
  followerCount: number;
  followingCount: number;
};

export async function getCreatorProfile(creatorId: string): Promise<CreatorProfile> {
  return request(`/api/v1/creators/${creatorId}`, (payload) => {
    const o = obj('creator', payload);
    return {
      accountId: str('creator', o, 'accountId'),
      displayName: str('creator', o, 'displayName'),
      followerCount: num('creator', o, 'followerCount'),
      followingCount: num('creator', o, 'followingCount'),
    };
  });
}

export type NotificationItem = {
  notificationId: string;
  type: string;
  message: string;
  relatedVideoId: string | null;
  read: boolean;
  createdAt: string;
};

export async function getNotifications(): Promise<NotificationItem[]> {
  return request('/api/v1/notifications', (payload) =>
    arr('notifications', payload).map((raw, i) => {
      const o = obj(`notifications[${i}]`, raw);
      return {
        notificationId: str(`notifications[${i}]`, o, 'notificationId'),
        type: str(`notifications[${i}]`, o, 'type'),
        message: str(`notifications[${i}]`, o, 'message'),
        relatedVideoId: nullableStr(`notifications[${i}]`, o, 'relatedVideoId'),
        read: bool(`notifications[${i}]`, o, 'read'),
        createdAt: str(`notifications[${i}]`, o, 'createdAt'),
      };
    }),
  );
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  await requestNoContent(`/api/v1/notifications/${notificationId}/read`, { method: 'POST' });
}

export type SearchHit = {
  videoId: string;
  creatorId: string;
  creatorDisplayName: string;
  publishedAt: string;
};

export type SearchResponse = {
  query: string;
  results: SearchHit[];
};

/** Milestone 7: matches published videos by creator display name. */
export async function search(query: string): Promise<SearchResponse> {
  return request(`/api/v1/search?q=${encodeURIComponent(query)}`, (payload) => {
    const o = obj('search', payload);
    return {
      query: str('search', o, 'query'),
      results: arr('search.results', o['results']).map((raw, i) => {
        const hit = obj(`search.results[${i}]`, raw);
        return {
          videoId: str(`search.results[${i}]`, hit, 'videoId'),
          creatorId: str(`search.results[${i}]`, hit, 'creatorId'),
          // Validated, not assumed: SearchPanel slices this string, and an
          // absent field previously threw at render into no error boundary.
          creatorDisplayName: str(`search.results[${i}]`, hit, 'creatorDisplayName'),
          publishedAt: str(`search.results[${i}]`, hit, 'publishedAt'),
        };
      }),
    };
  });
}
