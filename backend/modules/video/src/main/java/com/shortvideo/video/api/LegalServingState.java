package com.shortvideo.video.api;

/** Brief section 7. Fixed to CLEAR in the local MVP; no geographic legal policy yet. */
public enum LegalServingState {
    CLEAR,
    REVIEW_PENDING_ALLOW,
    REVIEW_PENDING_BLOCK,
    BLOCK_GLOBAL,
    BLOCK_BY_REGION,
    PRESERVATION_ONLY
}
