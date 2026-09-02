/**
 * Line-art icon set, drawn on a 24x24 grid so every glyph shares one optical
 * weight. Emoji were standing in for these, which is why the chrome looked
 * inconsistent across platforms -- an emoji is a different typeface (and a
 * different colour) on every OS, and none of them match each other.
 *
 * `filled` glyphs are solid shapes that take `currentColor`; `stroke` glyphs
 * inherit stroke-width from the shared `.icon` rule.
 */
// `| undefined` is spelled out because the project runs with
// exactOptionalPropertyTypes, where `p?: T` will not accept an explicit
// `undefined` forwarded from a caller's own optional prop.
type IconProps = { size?: number | undefined; className?: string | undefined };

function Svg({
  size = 24,
  className,
  filled,
  children,
}: IconProps & { filled?: boolean | undefined; children: React.ReactNode }) {
  return (
    <svg
      className={`icon${filled ? ' icon-filled' : ''}${className ? ` ${className}` : ''}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? 'currentColor' : 'none'}
      stroke={filled ? 'none' : 'currentColor'}
      strokeWidth={1.9}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export function HomeIcon(props: IconProps & { active?: boolean | undefined }) {
  return props.active ? (
    <Svg {...props} filled>
      <path d="M11.3 2.6a1.1 1.1 0 0 1 1.4 0l8.2 7a1.1 1.1 0 0 1 .4.85V20a1.4 1.4 0 0 1-1.4 1.4h-4.5a.9.9 0 0 1-.9-.9v-4.6a1.2 1.2 0 0 0-1.2-1.2h-2.6a1.2 1.2 0 0 0-1.2 1.2v4.6a.9.9 0 0 1-.9.9H4.1A1.4 1.4 0 0 1 2.7 20v-9.55c0-.33.14-.64.4-.85Z" />
    </Svg>
  ) : (
    <Svg {...props}>
      <path d="M3.4 10.2 12 3l8.6 7.2V20a1 1 0 0 1-1 1h-4.4v-5.4a1.2 1.2 0 0 0-1.2-1.2h-2.6a1.2 1.2 0 0 0-1.2 1.2V21H5.4a1 1 0 0 1-1-1Z" />
    </Svg>
  );
}

export function UsersIcon(props: IconProps & { active?: boolean | undefined }) {
  return (
    <Svg {...props} filled={props.active}>
      {props.active ? (
        <path d="M9 3.2a4 4 0 1 1 0 8 4 4 0 0 1 0-8Zm7.6 1.4a3.2 3.2 0 1 1 0 6.4 3.2 3.2 0 0 1 0-6.4ZM9 12.8c3.9 0 7 1.9 7 4.3v2.3a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-2.3c0-2.4 3.1-4.3 7-4.3Zm8.6-.4c2.6.28 4.4 1.68 4.4 3.5v2.4a1 1 0 0 1-1 1h-3.3v-2.2c0-1.8-.85-3.3-2.2-4.35a11 11 0 0 1 2.1-.35Z" />
      ) : (
        <>
          <circle cx="9" cy="7.2" r="3.6" />
          <path d="M2.8 19.6v-2.1c0-2.2 2.8-3.9 6.2-3.9s6.2 1.7 6.2 3.9v2.1" />
          <path d="M16.2 4.6a3.1 3.1 0 0 1 0 6.1" />
          <path d="M17.4 13.9c2.2.4 3.8 1.6 3.8 3.2v2.5" />
        </>
      )}
    </Svg>
  );
}

export function SearchIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="10.8" cy="10.8" r="7" />
      <path d="m16.1 16.1 4.4 4.4" />
    </Svg>
  );
}

export function InboxIcon(props: IconProps & { active?: boolean | undefined }) {
  return (
    <Svg {...props} filled={props.active}>
      {props.active ? (
        <path d="M12 2.4c-3.9 0-7 3.1-7 7v3.4l-1.6 3a1.3 1.3 0 0 0 1.15 1.9h4.2a3.3 3.3 0 0 0 6.5 0h4.2a1.3 1.3 0 0 0 1.15-1.9l-1.6-3V9.4c0-3.9-3.1-7-7-7Z" />
      ) : (
        <>
          <path d="M6 9.4a6 6 0 1 1 12 0v3.6l1.5 2.85a.8.8 0 0 1-.7 1.15H5.2a.8.8 0 0 1-.7-1.15L6 13Z" />
          <path d="M9.4 17.4a2.7 2.7 0 0 0 5.2 0" />
        </>
      )}
    </Svg>
  );
}

export function PlusIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M12 5.2v13.6M5.2 12h13.6" />
    </Svg>
  );
}

export function UserIcon(props: IconProps & { active?: boolean | undefined }) {
  return (
    <Svg {...props} filled={props.active}>
      {props.active ? (
        <path d="M12 2.8a4.6 4.6 0 1 1 0 9.2 4.6 4.6 0 0 1 0-9.2Zm0 10.8c4.4 0 8 2.2 8 4.9v1.6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-1.6c0-2.7 3.6-4.9 8-4.9Z" />
      ) : (
        <>
          <circle cx="12" cy="7.6" r="4.1" />
          <path d="M4.6 20.4v-1.5c0-2.5 3.3-4.5 7.4-4.5s7.4 2 7.4 4.5v1.5" />
        </>
      )}
    </Svg>
  );
}

export function HeartIcon(props: IconProps & { filled?: boolean | undefined }) {
  return (
    <Svg {...props} filled={props.filled}>
      <path d="M12 20.3s-7.9-4.6-9.35-9.4A5.3 5.3 0 0 1 12 6.35a5.3 5.3 0 0 1 9.35 4.55C19.9 15.7 12 20.3 12 20.3Z" />
    </Svg>
  );
}

export function CommentIcon(props: IconProps) {
  return (
    <Svg {...props} filled>
      <path d="M12 2.8c-5.3 0-9.6 3.6-9.6 8.1 0 2.55 1.4 4.83 3.6 6.32v3.35a.6.6 0 0 0 .93.5l3.4-2.2c.54.08 1.1.13 1.67.13 5.3 0 9.6-3.6 9.6-8.1S17.3 2.8 12 2.8Z" />
    </Svg>
  );
}

export function ShareIcon(props: IconProps) {
  return (
    <Svg {...props} filled>
      <path d="M13.6 3.4a.8.8 0 0 1 1.35-.58l7 6.6a.8.8 0 0 1 0 1.16l-7 6.6a.8.8 0 0 1-1.35-.58v-3.16c-4.6.12-7.9 1.5-10.1 4.32a.8.8 0 0 1-1.42-.58C2.7 11.1 6.9 7.5 13.6 7.05Z" />
    </Svg>
  );
}

export function VolumeOnIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M11 4.8 6.6 8.4H3.4a.8.8 0 0 0-.8.8v5.6a.8.8 0 0 0 .8.8h3.2l4.4 3.6a.6.6 0 0 0 1-.47V5.27a.6.6 0 0 0-1-.47Z" fill="currentColor" stroke="none" />
      <path d="M15.4 9a4.2 4.2 0 0 1 0 6M18.3 6.2a8 8 0 0 1 0 11.6" />
    </Svg>
  );
}

export function VolumeOffIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M11 4.8 6.6 8.4H3.4a.8.8 0 0 0-.8.8v5.6a.8.8 0 0 0 .8.8h3.2l4.4 3.6a.6.6 0 0 0 1-.47V5.27a.6.6 0 0 0-1-.47Z" fill="currentColor" stroke="none" />
      <path d="m15.6 9.6 5 4.8M20.6 9.6l-5 4.8" />
    </Svg>
  );
}

export function PlayIcon(props: IconProps) {
  return (
    <Svg {...props} filled>
      <path d="M7.6 4.2a.9.9 0 0 1 1.37-.77l10.4 7.02a.93.93 0 0 1 0 1.54L8.97 19a.9.9 0 0 1-1.37-.77Z" />
    </Svg>
  );
}

export function ChevronUpIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="m6.5 14.5 5.5-5.5 5.5 5.5" />
    </Svg>
  );
}

export function ChevronDownIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="m6.5 9.5 5.5 5.5 5.5-5.5" />
    </Svg>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="m6 6 12 12M18 6 6 18" />
    </Svg>
  );
}

export function UploadCloudIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M17 18.4a4.6 4.6 0 0 0 .4-9.17 5.8 5.8 0 0 0-11.13-1.5A4.4 4.4 0 0 0 6.6 18.4" />
      <path d="M12 21V10.4M8.6 13.6 12 10.2l3.4 3.4" />
    </Svg>
  );
}

export function CheckIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="m5 12.6 4.6 4.6L19 7.4" />
    </Svg>
  );
}

export function FlagIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M5.4 21V3.6M5.4 4.6h12.4a.5.5 0 0 1 .4.82l-2.9 3.66a.5.5 0 0 0 0 .62l2.9 3.66a.5.5 0 0 1-.4.82H5.4" />
    </Svg>
  );
}

export function SparkleIcon(props: IconProps) {
  return (
    <Svg {...props} filled>
      <path d="M12 2.6c.3 3.8 1.8 5.4 5.6 5.7-3.8.3-5.3 1.9-5.6 5.7-.3-3.8-1.8-5.4-5.6-5.7 3.8-.3 5.3-1.9 5.6-5.7ZM18.4 14c.18 2.1 1 2.95 3.1 3.15-2.1.2-2.92 1.03-3.1 3.15-.18-2.12-1-2.95-3.1-3.15 2.1-.2 2.92-1.05 3.1-3.15Z" />
    </Svg>
  );
}
