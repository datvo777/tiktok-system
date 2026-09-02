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
