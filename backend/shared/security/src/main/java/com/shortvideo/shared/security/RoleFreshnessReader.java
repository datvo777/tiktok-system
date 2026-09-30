package com.shortvideo.shared.security;

import java.util.Optional;
import java.util.Set;

/** Authoritative read of an account's current roles; Redis only accelerates it. */
public interface RoleFreshnessReader {

    /** @return empty if the account does not exist. */
    Optional<Set<String>> rolesOf(String accountId);
}
