import { describe, expect, it } from 'vitest';
import { dedupeByVideoId } from '../feedState';

describe('dedupeByVideoId', () => {
  it('keeps the first appearance of each video and the order', () => {
    const items = [
      { videoId: 'a', page: 0 },
      { videoId: 'b', page: 0 },
      { videoId: 'a', page: 1 },
      { videoId: 'c', page: 1 },
    ];
    expect(dedupeByVideoId(items)).toEqual([items[0], items[1], items[3]]);
  });
});
