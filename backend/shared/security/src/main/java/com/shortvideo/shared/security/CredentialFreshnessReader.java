package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.Optional;

/**
 * Authoritative read of when an account's password was last changed.
 *
 * <p>The account row is the source of truth (it is written in the same
 * transaction and the same statement as the password hash it belongs to); Redis
 * only accelerates reads of it. Mirrors the read/durable split {@code
 * DurableRevocationReader} uses for account suspension.
 */
public interface CredentialFreshnessReader {

    /** @return empty if the account does not exist. */
    Optional<Instant> passwordChangedAt(String accountId);
}
