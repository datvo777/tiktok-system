package com.shortvideo.account.domain;

import java.util.Locale;

/**
 * The one place an email is turned into its canonical form.
 *
 * <p>Anything that keys off an email — the account lookup, the per-account
 * login rate limiter, the admin search — must use this and nothing else. Two
 * call sites normalising the same address independently is how they drift:
 * one accepts {@code Victim@Email.com} and {@code victim@email.com} as the
 * same identity while the other treats them as different keys, and whatever
 * that second site guards (uniqueness, a rate limit) no longer actually
 * applies to the address as a whole.
 */
public final class Emails {

    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private Emails() {}
}
