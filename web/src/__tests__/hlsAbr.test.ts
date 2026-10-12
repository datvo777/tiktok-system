import { beforeEach, describe, expect, it } from 'vitest';
import {
  INITIAL_BANDWIDTH_ESTIMATE_BPS,
  rememberBandwidthEstimate,
  resetBandwidthEstimate,
  startingBandwidthEstimate,
} from '../hlsAbr';

describe('hls bandwidth memory', () => {
  beforeEach(resetBandwidthEstimate);

  it('starts from the default before anything has been measured', () => {
    expect(startingBandwidthEstimate()).toBe(INITIAL_BANDWIDTH_ESTIMATE_BPS);
  });

  it('starts the next player from the last measurement', () => {
    rememberBandwidthEstimate(4_200_000);
    expect(startingBandwidthEstimate()).toBe(4_200_000);
  });

  it.each([0, -1, NaN, Infinity])('ignores an unusable sample (%s)', (bad) => {
    rememberBandwidthEstimate(3_000_000);
    rememberBandwidthEstimate(bad);
    expect(startingBandwidthEstimate()).toBe(3_000_000);
  });
});
