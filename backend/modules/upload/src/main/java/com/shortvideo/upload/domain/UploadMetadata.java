package com.shortvideo.upload.domain;

import java.text.Normalizer;

/**
 * Title and description are immutable once the draft exists, so whatever is accepted
 * here is permanent: normalised to NFC (so visually identical text compares equal),
 * trimmed, and free of control characters (a description may keep line breaks and tabs).
 * Length is checked after normalisation, since NFC can change the character count.
 */
final class UploadMetadata {

    static final int MAX_TITLE = 150;
    static final int MAX_DESCRIPTION = 2000;

    private UploadMetadata() {}

    static String title(String raw) {
        String title = clean(raw, false);
        if (title == null || title.isEmpty()) {
            throw new UploadExceptions.InvalidMetadata("Title is required");
        }
        if (title.length() > MAX_TITLE) {
            throw new UploadExceptions.InvalidMetadata("Title must be at most " + MAX_TITLE + " characters");
        }
        return title;
    }

    /** @return the cleaned description, or null when absent or blank. */
    static String description(String raw) {
        String description = clean(raw, true);
        if (description == null || description.isEmpty()) {
            return null;
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new UploadExceptions.InvalidMetadata(
                    "Description must be at most " + MAX_DESCRIPTION + " characters");
        }
        return description;
    }

    private static String clean(String raw, boolean allowLineBreaks) {
        if (raw == null) {
            return null;
        }
        String normalised = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        for (int i = 0; i < normalised.length(); ) {
            int cp = normalised.codePointAt(i);
            boolean lineBreakOrTab = allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t');
            // ISO control characters plus the invisible format characters (zero-width,
            // bidi overrides) that let text read differently from how it is stored.
            if (!lineBreakOrTab && (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)) {
                throw new UploadExceptions.InvalidMetadata("Text contains characters that are not allowed");
            }
            i += Character.charCount(cp);
        }
        return normalised;
    }
}
