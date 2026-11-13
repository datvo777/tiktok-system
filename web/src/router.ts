/**
 * A small router over the History API.
 *
 * <p>The app previously kept its entire navigation state in a single
 * `useState<Panel>`, which meant it had no URLs at all. Three things followed
 * from that, all of them user-visible: the browser Back button exited the app
 * instead of closing a sheet, nothing could be bookmarked or linked, and the
 * share button produced a `/?v=<id>` link that no code anywhere read — every
 * shared link opened a generic feed.
 *
 * <p>Hand-written rather than a router dependency, for the same reason the HTTP
 * layer next door is: the surface is seven destinations, and this file is
 * shorter than the configuration a library would need. It is deliberately not a
 * general router — there is no nesting, no loaders, no params beyond an id.
 *
 * <h2>Shape of a URL</h2>
 *
 * The path names the destination and the query names the overlay on top of it,
 * so a sheet can be opened over any destination without inventing a URL for
 * every combination:
 *
 * <pre>
 *   /                        the feed
 *   /video/&lt;uuid&gt;            one video, the thing a share link points at
 *   /creator/&lt;uuid&gt;          a creator's profile
 *   …?sheet=search           any of the above, with a sheet open over it
 * </pre>
 */

/** The overlay sheets, keyed as the app already names them. */
export type Sheet = 'upload' | 'search' | 'notifications' | 'account' | 'myVideos' | 'favorites' | 'signIn';

/** What the stage shows underneath any sheet. */
export type View =
  | { kind: 'feed' }
  | { kind: 'video'; videoId: string }
  | { kind: 'creator'; creatorId: string };

export type Route = { view: View; sheet: Sheet | null };

/**
 * URL slugs, which are not the internal names: `notifications` reads better as
 * `inbox` in a URL, and `myVideos` has to be hyphenated. Kept as one map so the
 * two directions cannot drift.
 */
const SHEET_SLUG: Record<Sheet, string> = {
  signIn: 'sign-in',
  upload: 'upload',
  search: 'search',
  notifications: 'inbox',
  account: 'profile',
  myVideos: 'my-videos',
  favorites: 'favorites',
};

const SHEET_BY_SLUG = new Map<string, Sheet>(
  (Object.entries(SHEET_SLUG) as [Sheet, string][]).map(([sheet, slug]) => [slug, sheet]),
);

/**
 * Ids in this system are UUIDs. Validating the shape here means a hand-edited or
 * truncated URL falls back to the feed instead of reaching the API as a request
 * that can only 400.
 */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const FEED: Route = { view: { kind: 'feed' }, sheet: null };

/** Fired by {@link navigate}; `popstate` alone does not cover pushes we make ourselves. */
const ROUTE_CHANGE = 'app:routechange';

/**
 * @returns the parsed route, plus a canonical URL when the one given should be
 *   rewritten in place — a legacy `/?v=<id>` share link, or a path that did not
 *   resolve. The caller does that with `replaceState`, so the address bar agrees
 *   with what is on screen without adding a history entry.
 */
export function parseRoute(url: URL): { route: Route; canonical: string | null } {
  const sheetSlug = url.searchParams.get('sheet');
  const sheet = sheetSlug ? SHEET_BY_SLUG.get(sheetSlug) ?? null : null;

  // Links copied before this router existed. Accepted, then rewritten to the
  // path form so only one shape of URL is ever shared onward.
  const legacyVideo = url.searchParams.get('v');
  if (legacyVideo && UUID.test(legacyVideo)) {
    const route: Route = { view: { kind: 'video', videoId: legacyVideo.toLowerCase() }, sheet };
    return { route, canonical: toPath(route) };
  }

  const segments = url.pathname.split('/').filter(Boolean);

  if (segments.length === 0) {
    return { route: { view: { kind: 'feed' }, sheet }, canonical: null };
  }

  const [head, id] = segments;
  if (segments.length === 2 && id && UUID.test(id)) {
    if (head === 'video') {
      return { route: { view: { kind: 'video', videoId: id.toLowerCase() }, sheet }, canonical: null };
    }
    if (head === 'creator') {
      return { route: { view: { kind: 'creator', creatorId: id.toLowerCase() }, sheet }, canonical: null };
    }
  }

  // Anything unrecognised is the feed, and says so in the address bar rather
  // than leaving a URL on screen that does not describe the page.
  return { route: { view: { kind: 'feed' }, sheet }, canonical: toPath({ view: { kind: 'feed' }, sheet }) };
}

export function toPath(route: Route): string {
  const path =
    route.view.kind === 'video'
      ? `/video/${route.view.videoId}`
      : route.view.kind === 'creator'
        ? `/creator/${route.view.creatorId}`
        : '/';
  return route.sheet ? `${path}?sheet=${SHEET_SLUG[route.sheet]}` : path;
}

export function videoPath(videoId: string): string {
  return `/video/${videoId}`;
}

export function creatorPath(creatorId: string): string {
  return `/creator/${creatorId}`;
}

/** The absolute URL to hand someone, used by the share button. */
export function shareUrl(videoId: string): string {
  return `${window.location.origin}${videoPath(videoId)}`;
}

export function readRoute(): Route {
  const { route, canonical } = parseRoute(new URL(window.location.href));
  if (canonical && canonical !== currentPath()) {
    // replaceState, not push: rewriting a legacy or malformed URL should not
    // leave a history entry that Back would return the viewer to.
    window.history.replaceState(null, '', canonical);
  }
  return route;
}

function currentPath(): string {
  return `${window.location.pathname}${window.location.search}`;
}

/**
 * Marks the entries this app pushed, which is what lets {@link dismiss} tell
 * "there is somewhere to go back to" from "this tab opened straight onto a
 * shared link" — where `history.back()` would leave the site entirely.
 */
const OWN_ENTRY = { app: true };

export function navigate(to: string, options?: { replace?: boolean }): void {
  if (to === currentPath()) return;
  window.history[options?.replace ? 'replaceState' : 'pushState'](OWN_ENTRY, '', to);
  window.dispatchEvent(new Event(ROUTE_CHANGE));
}

/**
 * Closes an overlay by returning to where the viewer came from, so dismissing a
 * profile they opened from the feed puts them back at the feed rather than
 * pushing a third entry onto the stack. Falls back to a plain navigation when
 * this entry is the first one — a link opened in a fresh tab.
 */
export function dismiss(fallback = '/'): void {
  if ((window.history.state as { app?: boolean } | null)?.app) {
    window.history.back();
  } else {
    navigate(fallback, { replace: true });
  }
}

/**
 * Opens or closes a sheet without disturbing what is behind it, so dismissing
 * the search sheet returns to the video you were on rather than to the feed.
 */
export function setSheet(sheet: Sheet | null): void {
  navigate(toPath({ ...readRoute(), sheet }));
}

/** Subscribe to route changes from both Back/Forward and our own pushes. */
export function subscribe(listener: () => void): () => void {
  window.addEventListener('popstate', listener);
  window.addEventListener(ROUTE_CHANGE, listener);
  return () => {
    window.removeEventListener('popstate', listener);
    window.removeEventListener(ROUTE_CHANGE, listener);
  };
}

export { FEED };
