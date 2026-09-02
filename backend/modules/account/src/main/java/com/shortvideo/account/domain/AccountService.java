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
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactions;

    public AccountService(
            AccountJpaRepository repository,
            PasswordEncoder passwordEncoder,
            OutboxWriter outboxWriter,
            DurableRevocationWriter revocationWriter,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.outboxWriter = outboxWriter;
        this.revocationWriter = revocationWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * BCrypt at cost 10 is ~100ms of pure CPU. Doing it inside a transaction pins a
     * pooled connection for that whole time while performing no database work, so
     * twenty concurrent registrations would hold the entire default pool doing
     * nothing. The hash is computed first, then a transaction is opened around the
     * writes that actually need one.
     *
     * <p>{@code TransactionTemplate} rather than a second {@code @Transactional}
     * method on this class: a self-invoked call does not pass through the proxy, so
     * the annotation would be silently ignored and the outbox insert would no longer
     * commit atomically with the account row.
     */
    public AccountView register(String rawEmail, String rawPassword, String displayName) {
        String email = normalise(rawEmail);
        String passwordHash = passwordEncoder.encode(rawPassword);
        String trimmedName = displayName.trim();

        return transactions.execute(status -> {
            if (repository.existsByEmail(email)) {
                throw new AccountExceptions.EmailAlreadyRegistered("Email is already registered");
            }

            AccountEntity account =
                    new AccountEntity(UUID.randomUUID(), email, passwordHash, trimmedName, DEFAULT_ROLES);
            try {
                // Flush so the entity holds its assigned aggregate version before
                // the event is written; the outbox unique constraint is keyed on
                // that version. The flush also surfaces a lost race here, while it
                // can still be translated, rather than at commit.
                AccountEntity saved = repository.saveAndFlush(account);
                appendStateEvent(saved, EventTypes.ACCOUNT_REGISTERED, "registration");
                return toView(saved);
            } catch (DataIntegrityViolationException raceLost) {
                // existsByEmail is check-then-act with no lock, so two concurrent
                // registrations for one address both reach the insert.
                // account_email_key correctly rejects the loser; without this it
                // surfaces as a 500 while the identical sequential case answers 409.
                throw new AccountExceptions.EmailAlreadyRegistered("Email is already registered");
            }
        });
    }

    /**
     * Verifies the password outside any transaction, for the reason given on
     * {@link #register}. The lookup is a single query and Spring Data already wraps
     * repository reads in their own transaction, so none is needed here.
     */
    public AccountEntity authenticate(String rawEmail, String rawPassword) {
        Optional<AccountEntity> candidate = repository.findByEmail(normalise(rawEmail));

        if (candidate.isEmpty()) {
            // Same work either way, so a failed login does not leak which addresses
            // are registered through its response time.
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

    /**
     * {@code roles} is a free-text column, so it is parsed defensively.
     * {@code Set.of} threw {@code IllegalArgumentException} on a duplicate entry —
     * a {@code "USER,USER"} row turned every login into a 500 — and untrimmed
     * splitting turned {@code "USER, ADMIN"} into the authority {@code ROLE_ ADMIN},
     * which matches nothing and denies an apparently-correct admin with no error to
     * explain it.
     */
    public Set<String> rolesOf(AccountEntity account) {
        String roles = account.getRoles();
        if (roles == null || roles.isBlank()) {
            return Set.of(DEFAULT_ROLES);
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
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
