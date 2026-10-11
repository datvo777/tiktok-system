import qrcode from 'qrcode-generator';
import { useMemo } from 'react';

/** Quiet zone, in modules. The QR standard asks for four; scanners get unreliable with less. */
const QUIET = 4;

/**
 * A QR code drawn as an SVG, in the browser, from the text it is given. Nothing is sent anywhere: the
 * enrolment secret never leaves this page, which is why this is a library call and not an image URL.
 *
 * Always black on white, whatever the theme: a scanner needs the contrast and the quiet zone, and inverted
 * codes are not read by every app.
 */
export function QrCode({ value, label, size = 200 }: { value: string; label: string; size?: number }) {
  const { path, modules } = useMemo(() => {
    // Type 0 lets the library pick the smallest version that fits; 'M' survives a smudge or a glare.
    const qr = qrcode(0, 'M');
    qr.addData(value);
    qr.make();
    const count = qr.getModuleCount();
    let d = '';
    for (let row = 0; row < count; row += 1) {
      for (let col = 0; col < count; col += 1) {
        if (qr.isDark(row, col)) d += `M${col + QUIET} ${row + QUIET}h1v1h-1z`;
      }
    }
    return { path: d, modules: count + QUIET * 2 };
  }, [value]);

  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${modules} ${modules}`}
      shapeRendering="crispEdges"
      className="qr-code"
    >
      <rect width={modules} height={modules} fill="#ffffff" />
      <path d={path} fill="#000000" />
    </svg>
  );
}
