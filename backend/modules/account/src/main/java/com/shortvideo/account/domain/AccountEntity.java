package com.shortvideo.account.domain;

import com.shortvideo.account.api.AccountState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account", schema = "account")
public class AccountEntity {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** As the owner typed it. Uniqueness is enforced on {@link #handleLower}. */
    @Column(name = "handle", nullable = false, length = 30)
    private String handle;

    /** Canonical lower-case form; the unique index and every lookup match on this. */
    @Column(name = "handle_lower", nullable = false, length = 30)
    private String handleLower;

    /** Null when never set; see V26. */
    @Column(name = "bio", length = 300)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private AccountState state;

    @Column(name = "roles", nullable = false)
    private String roles;

    /** Optimistic concurrency and the aggregate version carried by events (Rule 10). */
    @Version
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {}

    public AccountEntity(
            UUID accountId, String email, String passwordHash, String displayName, String handle, String roles) {
        Instant now = Instant.now();
        this.accountId = accountId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.handle = handle;
        this.handleLower = handle.toLowerCase(java.util.Locale.ROOT);
        this.roles = roles;
        this.state = AccountState.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transitionTo(AccountState next) {
        this.state = next;
        this.updatedAt = Instant.now();
    }

    /**
     * @return true when something actually changed, so the caller can skip
     *     emitting an event and bumping the version for a no-op save.
     */
    public boolean updateProfile(String newDisplayName, String newBio) {
        boolean changed = false;
        if (newDisplayName != null && !newDisplayName.equals(this.displayName)) {
            this.displayName = newDisplayName;
            changed = true;
        }
        // A blank bio means "clear it": there is no useful distinction between an
        // empty bio and no bio, and storing one of each would make two rows that
        // render identically compare unequal.
        String normalisedBio = newBio == null ? null : (newBio.isBlank() ? null : newBio);
        if (newBio != null && !java.util.Objects.equals(normalisedBio, this.bio)) {
            this.bio = normalisedBio;
            changed = true;
        }
        if (changed) {
            this.updatedAt = Instant.now();
        }
        return changed;
    }

    /**
     * @return true when the handle actually changed, so the caller can skip a
     *     needless write and version bump.
     */
    public boolean changeHandle(String newHandle) {
        String lower = newHandle.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals(this.handleLower) && newHandle.equals(this.handle)) {
            return false;
        }
        this.handle = newHandle;
        this.handleLower = lower;
        this.updatedAt = Instant.now();
        return true;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }

    public UUID getAccountId() { return accountId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public String getHandle() { return handle; }
    public String getHandleLower() { return handleLower; }
    public AccountState getState() { return state; }
    public String getRoles() { return roles; }
    public long getAggregateVersion() { return aggregateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
