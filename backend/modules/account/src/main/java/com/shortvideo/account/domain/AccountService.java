package com.shortvideo.account.domain;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.shared.revocation.DurableRevocationWriter;
import com.shortvideo.shared.revocation.RevocationClearCommand;
import com.shortvideo.shared.revocation.RevocationCommand;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements AccountDirectory {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "account";
    private static final String DEFAULT_ROLES = "USER";

    /**
     * Compared against when the email is unknown, so a failed login costs the same
     * work either way and does not leak which addresses are registered.
     */
    private static final String DUMMY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOa8Nnhz1kFPqTBGvE0Y9WQ0v3wq1nJ8u";

    /** Revocation source type for an account suspension (brief section 16). */
    private static final String SUSPENSION_SOURCE = "ACCOUNT";

    private final AccountJpaRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxWriter outboxWriter;
    private final DurableRevocationWriter revocationWriter;

    public AccountService(
            AccountJpaRepository repository,
            PasswordEncoder passwordEncoder,
            OutboxWriter outboxWriter,
            DurableRevocationWriter revocationWriter) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.outboxWriter = outboxWriter;
        this.revocationWriter = revocationWriter;
    }

    /** Authoritative state update and outbox insert commit together. */
    @Transactional
    public AccountView register(String rawEmail, String rawPassword, String displayName) {
        String email = normalise(rawEmail);
        if (repository.existsByEmail(email)) {
            throw new AccountExceptions.EmailAlreadyRegistered("Email is already registered");
        }

        AccountEntity account = new AccountEntity(
                UUID.randomUUID(), email, passwordEncoder.encode(rawPassword), displayName.trim(), DEFAULT_ROLES);

        // Flush so the entity holds its assigned aggregate version before the event
        // is written; the outbox unique constraint is keyed on that version.
        AccountEntity saved = repository.saveAndFlush(account);
        appendStateEvent(saved, EventTypes.ACCOUNT_REGISTERED, "registration");
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public AccountEntity authenticate(String rawEmail, String rawPassword) {
        String email = normalise(rawEmail);
        Optional<AccountEntity> candidate = repository.findByEmail(email);

        if (candidate.isEmpty()) {
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw new AccountExceptions.InvalidCredentials("Invalid email or password");
        }

        AccountEntity account = candidate.get();
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            throw new AccountExceptions.InvalidCredentials("Invalid email or password");
        }
        if (account.getState() != AccountState.ACTIVE) {
            throw new AccountExceptions.AccountNotActive("Account is not active");
        }
        return account;
    }

    @Transactional
    public AccountView changeState(String accountId, AccountState next, String reason) {
        AccountEntity account = repository
                .findById(parseId(accountId))
                .orElseThrow(() -> new AccountExceptions.AccountNotFound("No such account"));

        long previousVersion = account.getAggregateVersion();
        account.transitionTo(next);
        AccountEntity saved = repository.saveAndFlush(account);

        String eventType = next == AccountState.ACTIVE
                ? EventTypes.ACCOUNT_REINSTATED
                : EventTypes.ACCOUNT_SUSPENDED;
        appendStateEvent(saved, eventType, reason);

        // Restrictive changes must fail closed and take effect immediately (Rule 8,
        // brief section 16); permissive changes may clear only their own source.
        if (next == AccountState.ACTIVE) {
            revocationWriter.clear(new RevocationClearCommand(
                    RevocationSubjects.ACCOUNT, saved.getAccountId().toString(), SUSPENSION_SOURCE, previousVersion));
        } else {
            revocationWriter.activate(new RevocationCommand(
                    RevocationSubjects.ACCOUNT,
                    saved.getAccountId().toString(),
                    SUSPENSION_SOURCE,
                    saved.getAggregateVersion(),
                    reason));
        }
        return toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> find(String accountId) {
        try {
            return repository.findById(parseId(accountId)).map(AccountService::toView);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    public Set<String> rolesOf(AccountEntity account) {
        return Set.of(account.getRoles().split(","));
    }

    private void appendStateEvent(AccountEntity account, String eventType, String reason) {
        var payload = new AccountEvents.AccountStateChanged(
                account.getAccountId().toString(),
                account.getDisplayName(),
                account.getState(),
                account.getAggregateVersion(),
                reason);

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.ACCOUNT,
                account.getAccountId().toString(),
                account.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }

    private static AccountView toView(AccountEntity account) {
        return new AccountView(
                account.getAccountId().toString(),
                account.getDisplayName(),
                account.getState(),
                account.getAggregateVersion(),
                account.getCreatedAt());
    }

    private static UUID parseId(String accountId) {
        return UUID.fromString(accountId);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
