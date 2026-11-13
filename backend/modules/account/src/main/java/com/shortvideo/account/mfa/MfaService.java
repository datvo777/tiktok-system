package com.shortvideo.account.mfa;

import com.shortvideo.account.domain.AccountService;
import com.shortvideo.shared.audit.AdminAction;
import com.shortvideo.shared.audit.AdminActionRecorder;
import com.shortvideo.shared.audit.AuditActions;
import com.shortvideo.shared.audit.AuditTargets;
import com.shortvideo.shared.security.CredentialFreshnessCache;
import com.shortvideo.shared.security.MfaProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The second factor: enrolling a TOTP secret, checking a code or a recovery code at sign-in, and the audit
 * trail around both. Present only when {@code shortvideo.mfa.enabled} is true.
 *
 * <p>Talks to the tables by SQL rather than through JPA entities: this is a handful of single-row statements
 * where an atomic {@code UPDATE ... WHERE} (to claim a time step, to spend a recovery code) is the point.
 */
@Service
@ConditionalOnProperty(prefix = "shortvideo.mfa", name = "enabled", havingValue = "true")
public class MfaService {

    static final String TYPE_TOTP = "TOTP";
    static final String PENDING = "PENDING";
    static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final MfaSecretCipher cipher;
    private final MfaProperties properties;
    private final AdminActionRecorder audit;
    private final CredentialFreshnessCache freshnessCache;

    public MfaService(
            JdbcTemplate jdbc,
            AccountService accounts,
            MfaSecretCipher cipher,
            MfaProperties properties,
            AdminActionRecorder audit,
            CredentialFreshnessCache freshnessCache) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.cipher = cipher;
        this.properties = properties;
        this.audit = audit;
        this.freshnessCache = freshnessCache;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveFactor(String accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM account.mfa_factor WHERE account_id = ? AND type = ? AND state = ?",
                Integer.class, UUID.fromString(accountId), TYPE_TOTP, ACTIVE);
        return n != null && n > 0;
    }

    /**
     * Starts enrolment: a fresh secret, kept pending until a code made from it is shown to be right.
     * Calling again before confirming replaces the pending secret.
     *
     * @throws com.shortvideo.account.domain.AccountExceptions.InvalidCredentials when the password is wrong
     * @throws MfaExceptions.AlreadyEnrolled when a factor is already active
     */
    @Transactional
    public Enrollment startEnrollment(String accountId, String email, String password) {
        accounts.requirePassword(accountId, password);
        if (hasActiveFactor(accountId)) {
            throw new MfaExceptions.AlreadyEnrolled();
        }
        UUID account = UUID.fromString(accountId);
        jdbc.update("DELETE FROM account.mfa_factor WHERE account_id = ? AND type = ? AND state = ?",
                account, TYPE_TOTP, PENDING);
        byte[] secret = Totp.newSecret();
        jdbc.update(
                "INSERT INTO account.mfa_factor (factor_id, account_id, type, state, secret_enc, key_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account, TYPE_TOTP, PENDING, cipher.seal(secret), cipher.keyId(),
                Timestamp.from(Instant.now()));
        return new Enrollment(Base32.encode(secret), Totp.uri(properties.getIssuer(), email, secret));
    }

    /**
     * Finishes enrolment with a code from the app, activates the factor and returns the recovery codes, which
     * are not shown again. Ends every session signed in before this moment.
     *
     * @throws MfaExceptions.NoPendingEnrollment when {@link #startEnrollment} was not called first
     * @throws MfaExceptions.InvalidCode when the code does not match
     */
    @Transactional
    public List<String> confirmEnrollment(String accountId, String code) {
        UUID account = UUID.fromString(accountId);
        List<Pending> pending = jdbc.query(
                "SELECT factor_id, secret_enc FROM account.mfa_factor WHERE account_id = ? AND type = ? AND state = ?",
                (rs, n) -> new Pending((UUID) rs.getObject("factor_id"), rs.getBytes("secret_enc")),
                account, TYPE_TOTP, PENDING);
        if (pending.isEmpty()) {
            throw new MfaExceptions.NoPendingEnrollment();
        }
        Pending factor = pending.get(0);
        Instant now = Instant.now();
        long step = Totp.match(cipher.open(factor.secret()), code, Totp.stepAt(now.getEpochSecond()), 0);
        if (step < 0) {
            throw new MfaExceptions.InvalidCode();
        }
        jdbc.update(
                "UPDATE account.mfa_factor SET state = ?, confirmed_at = ?, last_used_step = ? WHERE factor_id = ?",
                ACTIVE, Timestamp.from(now), step, factor.id());

        List<String> codes = RecoveryCodes.generate();
        jdbc.update("DELETE FROM account.mfa_recovery_code WHERE account_id = ?", account);
        for (String recovery : codes) {
            jdbc.update("INSERT INTO account.mfa_recovery_code (account_id, code_hash) VALUES (?, ?)",
                    account, RecoveryCodes.hash(recovery));
        }
        endEarlierSessions(accountId, account, now);
        audit.record(AdminAction.of(accountId, AuditActions.MFA_ENROLLED, AuditTargets.ACCOUNT, accountId));
        return codes;
    }

    /**
     * Checks the second step of a sign-in: a code from the app, or a recovery code. A recovery code is spent
     * by this call.
     *
     * @return whether it was accepted
     */
    @Transactional
    public boolean verifyAtSignIn(String accountId, String code, String recoveryCode) {
        UUID account = UUID.fromString(accountId);
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            int spent = jdbc.update(
                    "UPDATE account.mfa_recovery_code SET used_at = ? WHERE account_id = ? AND code_hash = ? AND used_at IS NULL",
                    Timestamp.from(Instant.now()), account, RecoveryCodes.hash(recoveryCode));
            if (spent == 1) {
                audit.record(AdminAction.of(accountId, AuditActions.MFA_RECOVERY_USED, AuditTargets.ACCOUNT, accountId));
                return true;
            }
            return false;
        }
        List<Active> factors = jdbc.query(
                "SELECT factor_id, secret_enc, last_used_step FROM account.mfa_factor "
                        + "WHERE account_id = ? AND type = ? AND state = ?",
                (rs, n) -> new Active((UUID) rs.getObject("factor_id"), rs.getBytes("secret_enc"), rs.getLong("last_used_step")),
                account, TYPE_TOTP, ACTIVE);
        if (factors.isEmpty()) {
            return false;
        }
        Active factor = factors.get(0);
        long step = Totp.match(
                cipher.open(factor.secret()), code, Totp.stepAt(Instant.now().getEpochSecond()), factor.lastUsedStep());
        if (step < 0) {
            return false;
        }
        // Claims the step. Of two requests carrying the same code at the same moment, only one updates a row.
        return jdbc.update(
                        "UPDATE account.mfa_factor SET last_used_step = ? WHERE factor_id = ? AND last_used_step < ?",
                        step, factor.id(), step)
                == 1;
    }

    private void endEarlierSessions(String accountId, UUID account, Instant now) {
        jdbc.update("UPDATE account.account SET mfa_changed_at = ? WHERE account_id = ?", Timestamp.from(now), account);
        // What JdbcCredentialFreshnessReader would read back: the later of the two, once this commits.
        Instant changedAt = jdbc.queryForObject(
                "SELECT GREATEST(password_changed_at, COALESCE(mfa_changed_at, password_changed_at)) FROM account.account WHERE account_id = ?",
                Instant.class, account);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    freshnessCache.put(accountId, changedAt);
                }
            });
        }
    }

    public record Enrollment(String secret, String otpauthUri) {}

    private record Pending(UUID id, byte[] secret) {}

    private record Active(UUID id, byte[] secret, long lastUsedStep) {}
}
