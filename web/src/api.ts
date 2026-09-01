// Every request is same-origin thanks to the Vite proxy, so the browser attaches
// the SameSite=Lax session cookie automatically. `credentials` is deliberately
// left at its default: `include` is for cross-origin calls, and needing it here
// would mean the proxy is misconfigured.

export type LoginResponse = {
  accountId: string;
  token: string;
  expiresAt: string;
};

async function readError(response: Response): Promise<string> {
  try {
    const problem = await response.json();
    return problem.detail ?? problem.title ?? `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}

export async function register(email: string, password: string, displayName: string): Promise<void> {
  const response = await fetch('/api/v1/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, displayName }),
  });
  if (!response.ok) throw new Error(await readError(response));
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

/**
 * Probes the media gateway with no Authorization header, the way hls.js and the
 * video element do. Expect 401 before login; after login and a preview session
 * (Milestone 2), the gateway actually streams bytes.
 */
export async function probeMedia(videoId: string): Promise<number> {
  const response = await fetch(`/media/videos/${videoId}/1/master.m3u8`);
  return response.status;
}

export type CreateUploadResponse = {
  uploadId: string;
  videoId: string;
  uploadUrl: string;
  expiresAt: string;
};

export type UploadResponse = {
  uploadId: string;
  videoId: string;
  status: 'PENDING' | 'COMPLETED' | 'EXPIRED';
  completedSizeBytes: number | null;
};

export type VideoResponse = {
  videoId: string;
  processingState: 'CREATED' | 'UPLOADING' | 'UPLOADED' | 'TRANSCODING' | 'READY' | 'FAILED' | 'EXPIRED';
  processingVersion: number | null;
  durabilityState: 'PENDING' | 'DURABLE';
  assetLifecycleState:
    | 'ACTIVE'
    | 'REJECTED_RETAINED'
    | 'DELETE_SCHEDULED'
    | 'DELETION_IN_PROGRESS'
    | 'QUARANTINED'
    | 'DELETED'
    | 'RESTORING';
  failureClass: string | null;
  pollAfterMs: number | null;
};

export type PlaybackSessionResponse = {
  videoId: string;
  processingVersion: number;
  mode: 'OWNER_PREVIEW' | 'PUBLIC';
  expiresAt: string;
};

/** Creates the upload session; the video draft is created in the same transaction (brief section 7.1). */
export async function createUpload(): Promise<CreateUploadResponse> {
  const response = await fetch('/api/v1/uploads', { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

/**
 * PUTs the file straight to MinIO with the presigned URL. Deliberately
 * cross-origin and cookie-free — this request must not set `credentials`
 * (brief section 12.1).
 */
export async function putToPresignedUrl(uploadUrl: string, file: File): Promise<void> {
  const response = await fetch(uploadUrl, { method: 'PUT', body: file });
  if (!response.ok) throw new Error(`Upload to storage failed: HTTP ${response.status}`);
}

export async function completeUpload(uploadId: string): Promise<UploadResponse> {
  const response = await fetch(`/api/v1/uploads/${uploadId}/complete`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function getVideo(videoId: string): Promise<VideoResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}`);
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

/** Owner-only; sets the path-scoped sv_playback cookie the gateway requires (brief section 8). */
export async function createPreviewSession(videoId: string): Promise<PlaybackSessionResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}/preview-playback-session`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export type PublicationResponse = {
  videoId: string;
  state: 'PRIVATE' | 'PUBLISH_PENDING' | 'PUBLISHED' | 'SUSPENDED' | 'REMOVED';
  intent: boolean;
};

/** Owner-only; the coordinator publishes only once processing and moderation both agree (brief section 8). */
export async function publishVideo(videoId: string): Promise<PublicationResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}/publish`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

/** Requires the full public eligibility invariant to pass (brief section 8); denies until approved and published. */
export async function createPublicSession(videoId: string): Promise<PlaybackSessionResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}/public-playback-session`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export type AppealResponse = {
  videoId: string;
  state: 'NONE' | 'UNDER_APPEAL' | 'REVIEWING' | 'APPROVED' | 'DENIED' | 'ESCALATED';
  reason: string | null;
  decisionReason: string | null;
};

/** Owner-only; only accepted while moderation state is REJECTED (brief section 18, Milestone 6). */
export async function submitAppeal(videoId: string, reason: string): Promise<AppealResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}/appeals`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export type FeedItem = { videoId: string; creatorId: string };
export type FeedResponse = { page: number; items: FeedItem[] };

export async function getFeed(page = 0): Promise<FeedResponse> {
  const response = await fetch(`/api/v1/feed?page=${page}`);
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function likeVideo(videoId: string): Promise<void> {
  const response = await fetch(`/api/v1/videos/${videoId}/likes`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
}

export async function unlikeVideo(videoId: string): Promise<void> {
  const response = await fetch(`/api/v1/videos/${videoId}/likes`, { method: 'DELETE' });
  if (!response.ok) throw new Error(await readError(response));
}

export type CommentResponse = { commentId: string; videoId: string; accountId: string; body: string; createdAt: string };

export async function commentOnVideo(videoId: string, body: string): Promise<CommentResponse> {
  const response = await fetch(`/api/v1/videos/${videoId}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body }),
  });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export type CreatorProfile = {
  accountId: string;
  displayName: string;
  accountState: string;
  followerCount: number;
  followingCount: number;
};

export async function getCreatorProfile(creatorId: string): Promise<CreatorProfile> {
  const response = await fetch(`/api/v1/creators/${creatorId}`);
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
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
  const response = await fetch('/api/v1/notifications');
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  const response = await fetch(`/api/v1/notifications/${notificationId}/read`, { method: 'POST' });
  if (!response.ok) throw new Error(await readError(response));
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
  const response = await fetch(`/api/v1/search?q=${encodeURIComponent(query)}`);
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}
