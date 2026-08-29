package com.shortvideo.shared.revocation;

/**
 * Authoritative revocation read (brief section 8, Rule 12).
 *
 * <p>The gateway consults this after the Redis deny fast-path fails to find an
 * entry — Redis absence never authorizes; only this durable check, or an explicit
 * durable eligibility allow read afterwards, may permit delivery.
 */
public interface DurableRevocationReader {

    boolean isActive(String subjectType, String subjectId);
}
