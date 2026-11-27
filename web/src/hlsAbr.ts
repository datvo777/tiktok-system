/**
 * Bandwidth memory shared by every hls.js instance in the page.
 *
 * The feed creates one player per slide, and hls.js starts each new instance
 * from a fixed guess (500 kbps) rather than from what the previous slide just
 * measured. Against this app's ladder that guess selects the smallest rung, so
 * every slide opened as a blurred 270x480 picture and only sharpened after its
 * first segment arrived. Carrying the last measurement into the next instance
 * lets it start on the rung the connection can actually sustain.
 */

/**
 * Used before anything has been measured. Sits between the two lower rungs'
 * advertised bandwidths (about 0.5 and 1.1 Mbps) so a first-ever load starts on
 * the middle rung instead of the smallest; a wrong guess corrects itself after
 * one segment either way.
 */
export const INITIAL_BANDWIDTH_ESTIMATE_BPS = 1_500_000;

let lastEstimateBps: number | null = null;

/** Ignores values hls.js reports before it has a sample (0, NaN, Infinity). */
export function rememberBandwidthEstimate(bps: number): void {
  if (Number.isFinite(bps) && bps > 0) {
    lastEstimateBps = bps;
  }
}

export function startingBandwidthEstimate(): number {
  return lastEstimateBps ?? INITIAL_BANDWIDTH_ESTIMATE_BPS;
}

/** Test seam: the estimate is module state. */
export function resetBandwidthEstimate(): void {
  lastEstimateBps = null;
}
