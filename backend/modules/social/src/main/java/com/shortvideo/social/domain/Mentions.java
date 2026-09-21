package com.shortvideo.social.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls {@code @handle} candidates out of free-text comment bodies.
 *
 * <p>Deliberately loose: this only has to narrow the body down to plausible
 * handle-shaped tokens (3-30 characters, matching account's own handle length
 * bounds, and not preceded by a word character or {@code .}/{@code @} so an
 * email address is not mistaken for a mention). It does not attempt the full
 * validation {@code Handles.normalise} does — a candidate that is malformed,
 * reserved, or simply belongs to nobody just fails to resolve, the same way a
 * typo in a real handle would.
 */
final class Mentions {

    private static final Pattern CANDIDATE = Pattern.compile("(?<![\\w.@])@([A-Za-z0-9_]{3,30})\\b");

    static Set<String> parse(String body) {
        Set<String> handles = new LinkedHashSet<>();
        if (body == null) {
            return handles;
        }
        Matcher matcher = CANDIDATE.matcher(body);
        while (matcher.find()) {
            handles.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return handles;
    }

    private Mentions() {}
}
