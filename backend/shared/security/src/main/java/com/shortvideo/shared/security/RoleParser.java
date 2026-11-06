package com.shortvideo.shared.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single definition of how the free-text {@code account.roles} column becomes a role
 * set, shared by the account module (login/refresh) and the filter's freshness check
 * so a token and the live row can never disagree about what a stored string means.
 *
 * <p>Parsed defensively: {@code Set.of} threw on a duplicate ({@code "USER,USER"}),
 * and untrimmed splitting turned {@code "USER, ADMIN"} into {@code ROLE_ ADMIN}.
 */
public final class RoleParser {

    public static final String DEFAULT_ROLE = "USER";

    private RoleParser() {}

    public static Set<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of(DEFAULT_ROLE);
        }
        Set<String> roles = Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return roles.isEmpty() ? Set.of(DEFAULT_ROLE) : roles;
    }
}
