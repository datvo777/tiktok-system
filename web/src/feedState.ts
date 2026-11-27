/**
 * Keeps the first appearance of each video. Pages cut from two different
 * rankings can overlap, and a repeat would be a duplicate React key.
 */
export function dedupeByVideoId<T extends { videoId: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.videoId)) return false;
    seen.add(item.videoId);
    return true;
  });
}
