/** Small presentational pieces shared by more than one screen. */

/**
 * Stable pseudo-random hue from an id. Accounts here have no profile pictures,
 * only UUIDs, so this is what keeps one creator the same colour everywhere
 * (feed rail, search results, sidebar) instead of every avatar being one flat
 * grey. Any cheap string hash would do; this is the classic djb2-style mix.
 */
export function avatarHue(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  return Math.abs(hash) % 360;
}

export function Avatar({
  seed,
  label,
  size = 'md',
  className,
}: {
  seed: string;
  label?: string | undefined;
  size?: 'sm' | 'md' | 'lg' | undefined;
  className?: string | undefined;
}) {
  const initial = (label ?? seed).trim().slice(0, 1).toUpperCase() || '?';
  const sizeClass = size === 'md' ? '' : ` avatar-${size}`;
  return (
    <span
      className={`avatar${sizeClass}${className ? ` ${className}` : ''}`}
      style={{ '--h': avatarHue(seed) } as React.CSSProperties}
      aria-hidden="true"
    >
      {initial}
    </span>
  );
}

/** A creator handle derived from the account id, since there are no usernames. */
export function handleFor(creatorId: string): string {
  return `@${creatorId.replace(/-/g, '').slice(0, 10)}`;
}

/** 1200 -> "1.2K": count labels have room for four characters, not four digits. */
export function formatCount(value: number): string {
  if (value < 1000) return String(value);
  if (value < 1_000_000) return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}K`.replace('.0', '');
  return `${(value / 1_000_000).toFixed(1)}M`.replace('.0', '');
}

/**
 * Timestamps arrive as ISO strings; a wall-clock string is noise in a list you
 * skim, so show the age instead and keep the exact value in the tooltip.
 */
export function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const seconds = Math.max(0, (Date.now() - then) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = seconds / 60;
  if (minutes < 60) return `${Math.floor(minutes)}m ago`;
  const hours = minutes / 60;
  if (hours < 24) return `${Math.floor(hours)}h ago`;
  const days = hours / 24;
  if (days < 7) return `${Math.floor(days)}d ago`;
  return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
