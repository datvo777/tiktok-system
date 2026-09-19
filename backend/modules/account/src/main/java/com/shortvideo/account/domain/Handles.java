package com.shortvideo.account.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules a handle has to satisfy, in one place.
 *
 * <p>Deliberately narrow. A handle is typed by other people from memory, read
 * aloud, and compared by eye against an impersonator's, so the character set is
 * restricted to what cannot be confused: ASCII letters, digits, underscore.
 * Leading and trailing separators are rejected because {@code @_dat} and
 * {@code @dat_} read as the same name as {@code @dat} at a glance, which is
 * exactly the ambiguity an impersonation attempt wants.
 */
public final class Handles {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    private static final Pattern SHAPE = Pattern.compile("[a-z0-9](?:[a-z0-9_]*[a-z0-9])?");

    /**
     * Handles that must never belong to a person, because a URL or a label using
     * one would be ambiguous with something the platform itself owns.
     */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "admin", "administrator", "moderator", "mod", "staff", "support", "help", "official",
            "root", "system", "api", "internal", "media", "video", "videos", "creator", "creators",
            "search", "feed", "inbox", "upload", "settings", "profile", "favorites", "about",
            "terms", "privacy", "login", "logout", "signup", "register", "me", "you", "null",
            "undefined", "short", "shortvideo");

    /**
     * @return the canonical lower-case form, for storing and for the uniqueness
     *     index.
     * @throws AccountExceptions.InvalidHandle if the value is not usable as a handle.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            throw new AccountExceptions.InvalidHandle("Pick a username");
        }
        // A leading @ is how people write a handle, not part of it.
        String trimmed = raw.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.length() < MIN_LENGTH || lower.length() > MAX_LENGTH) {
            throw new AccountExceptions.InvalidHandle(
                    "A username is between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!SHAPE.matcher(lower).matches()) {
            throw new AccountExceptions.InvalidHandle(
                    "A username can use letters, numbers and underscores, and cannot start or end with an underscore");
        }
        if (RESERVED.contains(lower)) {
            throw new AccountExceptions.InvalidHandle("That username is not available");
        }
        return lower;
    }

    /**
     * A handle for a brand-new account, derived from the display name they just
     * gave. Registration should not stop to make someone invent a second name;
     * they can change it afterwards.
     *
     * <p>Returns a <em>candidate</em>: the caller still has to resolve a
     * collision, because uniqueness belongs to the database, not to this method.
     */
    static String suggestFrom(String displayName, String accountId) {
        String stripped = displayName == null
                ? ""
                : displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        // Trim separators the shape rule would reject, then pad from the id if
        // what is left is too short -- a display name of "李雷" strips to nothing.
        stripped = stripped.replaceAll("^_+", "").replaceAll("_+$", "");
        if (stripped.length() < MIN_LENGTH) {
            stripped = "user" + accountId.replace("-", "").substring(0, 6);
        }
        return stripped.length() > MAX_LENGTH ? stripped.substring(0, MAX_LENGTH) : stripped;
    }

    /**
     * Appends a numeric suffix, keeping the result inside {@link #MAX_LENGTH} by
     * trimming the stem rather than the suffix — the suffix is what makes it
     * unique, so it is the part that cannot be cut.
     */
    static String withSuffix(String stem, int suffix) {
        String tail = String.valueOf(suffix);
        int room = MAX_LENGTH - tail.length();
        String head = stem.length() > room ? stem.substring(0, room) : stem;
        return (head + tail).replaceAll("^_+", "");
    }

    private Handles() {}
}
