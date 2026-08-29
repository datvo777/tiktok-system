package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.Set;

/** The verified caller identity carried in the SecurityContext. */
public record AuthenticatedAccount(String accountId, Set<String> roles, Instant expiresAt) {}
