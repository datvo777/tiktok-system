import { describe, expect, it } from 'vitest';
import { parseRoute, toPath, videoPath, creatorPath } from '../router';

const VIDEO = '3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b';
const CREATOR = '9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a';

function parse(url: string) {
  return parseRoute(new URL(url, 'https://short.example'));
}

describe('parseRoute', () => {
  it('reads the feed at the root', () => {
    expect(parse('/').route).toEqual({ view: { kind: 'feed' }, sheet: null });
  });

  it('reads a video path', () => {
    expect(parse(`/video/${VIDEO}`).route.view).toEqual({ kind: 'video', videoId: VIDEO });
  });

  it('reads a creator path', () => {
    expect(parse(`/creator/${CREATOR}`).route.view).toEqual({ kind: 'creator', creatorId: CREATOR });
  });

  it('reads a sheet from the query, over any view', () => {
    expect(parse('/?sheet=inbox').route.sheet).toBe('notifications');
    expect(parse(`/video/${VIDEO}?sheet=search`).route).toEqual({
      view: { kind: 'video', videoId: VIDEO },
      sheet: 'search',
    });
  });

  it('ignores a sheet slug it does not know', () => {
    expect(parse('/?sheet=nonsense').route.sheet).toBeNull();
  });

  // The bug this router was written for: share produced `/?v=<id>` and nothing
  // in the app ever read it, so every shared link opened a generic feed.
  it('accepts a legacy ?v= link and rewrites it to the path form', () => {
    const { route, canonical } = parse(`/?v=${VIDEO}`);
    expect(route.view).toEqual({ kind: 'video', videoId: VIDEO });
    expect(canonical).toBe(`/video/${VIDEO}`);
  });

  it('falls back to the feed for an id that is not a UUID, and says so in the URL', () => {
    const { route, canonical } = parse('/video/not-a-uuid');
    expect(route.view).toEqual({ kind: 'feed' });
    expect(canonical).toBe('/');
  });

  it('lower-cases an upper-cased id so one video has one URL', () => {
    expect(parse(`/video/${VIDEO.toUpperCase()}`).route.view).toEqual({ kind: 'video', videoId: VIDEO });
  });

  it('round-trips every route through toPath', () => {
    for (const url of ['/', `/video/${VIDEO}`, `/creator/${CREATOR}`, '/?sheet=favorites', `/video/${VIDEO}?sheet=upload`]) {
      expect(toPath(parse(url).route)).toBe(url);
    }
  });
});

describe('path builders', () => {
  it('builds the paths parseRoute reads back', () => {
    expect(parse(videoPath(VIDEO)).route.view).toEqual({ kind: 'video', videoId: VIDEO });
    expect(parse(creatorPath(CREATOR)).route.view).toEqual({ kind: 'creator', creatorId: CREATOR });
  });
});
