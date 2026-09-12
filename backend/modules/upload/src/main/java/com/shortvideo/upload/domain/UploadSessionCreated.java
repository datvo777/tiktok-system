package com.shortvideo.upload.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A presigned upload session.
 *
 * @param uploadUrl where the client posts the multipart form
 * @param formFields the presigned policy fields, which must be sent as form parts
 *     ahead of the file part. The policy pins the object key and caps the body at
 *     {@code maxBytes}, so the object store rejects an oversized upload at write
 *     time rather than accepting it and failing the check afterwards.
 * @param maxBytes the cap the policy encodes, echoed so a client can reject an
 *     oversized file before spending the upload
 */
public record UploadSessionCreated(
        String uploadId,
        String videoId,
        String uploadUrl,
        Map<String, String> formFields,
        long maxBytes,
        Instant expiresAt) {}
